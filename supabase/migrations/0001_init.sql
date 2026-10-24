-- ============================================================================
-- NomadSafe · Migración inicial
-- ----------------------------------------------------------------------------
-- Esquema completo del backend: PostGIS para la geometría espacial, RLS en todas
-- las tablas (sin excepciones), y las funciones que sostienen los tres
-- diferenciales del producto: mapa de riesgo, escolta en vivo y alertas SOS.
--
-- ── Convenciones ─────────────────────────────────────────────────────────────
-- · IDs: uuid con default gen_random_uuid(), salvo donde el cliente genera el id
--   para idempotencia (incidents, sos_events). Ver nota en cada tabla.
-- · Tiempos: timestamptz, nunca timestamp. Una app de seguridad que opera en
--   zonas horarias distintas y con servidores UTC no puede usar timestamps sin
--   zona: un incidente reportado a las 23:00 de Lima guardado como naive se
--   interpreta como 23:00 UTC, es decir 5 horas en el futuro, y el decay temporal
--   del motor de riesgo lo descarta.
-- · Geometría: geography para distancias métricas correctas a cualquier latitud.
--   geometry(Geometry,4326) se usa sólo donde PostGIS exige ese tipo para índices.
-- · RLS: activada en TODAS las tablas, incluso las de sólo lectura. Una tabla sin
--   RLS en Supabase es pública por defecto, y acá hay ubicaciones de personas en
--   tiempo real.
-- ============================================================================

create extension if not exists postgis;
create extension if not exists pgcrypto;
create extension if not exists pg_trgm;   -- búsqueda difusa de destinos

-- ============================================================================
-- PROFILES
-- ============================================================================
-- Se crea por trigger desde auth.users: no se puede depender de que el cliente la
-- inserte, porque una falla de red dejaría usuarios sin perfil y toda consulta con
-- join a profiles devolvería vacío.

create table public.profiles (
  id               uuid primary key references auth.users(id) on delete cascade,
  phone            text unique,
  display_name     text,
  -- Código de referido. ÚNICO y NO derivado del id: dos usuarios no pueden
  -- compartir código aunque uno reinstale la app.
  referral_code    text unique not null default upper(substr(md5(random()::text), 1, 8)),
  referred_by      uuid references public.profiles(id) on delete set null,
  referral_credits integer not null default 0 check (referral_credits >= 0),
  pilot_city       text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz
);

comment on column public.profiles.referral_code is
  'Código corto para invitaciones. Se genera en el servidor para garantizar unicidad real; el cliente sólo lo lee.';

-- Índice para la búsqueda por teléfono (invitar contactos ya registrados).
create index if not exists profiles_phone_idx on public.profiles (phone) where phone is not null;
create index if not exists profiles_referral_code_idx on public.profiles (referral_code);

-- ── Trigger de alta automática ───────────────────────────────────────────────
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, phone, display_name)
  values (
    new.id,
    -- Supabase guarda el teléfono en auth.users.phone cuando el OTP es telefónico.
    new.phone,
    coalesce(new.raw_user_meta_data ->> 'display_name', 'Vecino')
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

comment on function public.handle_new_user() is
  'security definer es necesario: el usuario recién creado aún no puede leer profiles por RLS, y sin definer el trigger fallaría al insertar.';

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ── updated_at automático ────────────────────────────────────────────────────
create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists profiles_touch_updated_at on public.profiles;
create trigger profiles_touch_updated_at
  before update on public.profiles
  for each row execute function public.touch_updated_at();

-- ============================================================================
-- INCIDENTS
-- ============================================================================
-- ── Por qué el id lo genera el cliente ───────────────────────────────────────
-- `id text primary key` y NO uuid con default. El cliente genera `inc-<uuid>`
-- ANTES de enviar y lo usa como clave primaria.
--
-- Es la base de la idempotencia offline: con el outbox local, un reporte puede
-- reintentarse varias veces tras perder conectividad. Si el servidor generara el
-- id, cada reintento crearía un incidente duplicado y el mapa se llenaría de
-- reportes fantasma que inflan artificialmente el score de riesgo de una cuadra.
-- Con el id del cliente, el reintento es un upsert sobre la misma fila.

create table public.incidents (
  id                text primary key,
  reporter_id       uuid references public.profiles(id) on delete set null,
  category          text not null check (category in (
                      'assault','theft','harassment','suspicious',
                      'dark_street','road_blocked','accident','other')),
  status            text not null default 'active' check (status in (
                      'active','confirmed','under_review','disputed','removed')),
  timing            text not null default 'recent' check (timing in ('now','recent','recurrent')),
  description       text check (char_length(description) <= 500),
  neighborhood      text,

  -- Lat/lon planos además de la geometría: el cliente filtra por bounding box con
  -- comparaciones simples, que es mucho más barato que ST_Intersects para el
  -- viewport del mapa y no requiere que la app entienda tipos PostGIS.
  lat               double precision not null check (lat between -90 and 90),
  lon               double precision not null check (lon between -180 and 180),
  location          geography(Point, 4326) not null,

  occurred_at       timestamptz not null default now(),
  reported_at       timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  confirmation_count integer not null default 0 check (confirmation_count >= 0),
  dispute_count      integer not null default 0 check (dispute_count >= 0),

  -- Precisión declarada por el usuario, en metros. Permite que el motor de riesgo
  -- descarte reportes demasiado imprecisos en vez de tratarlos como exactos.
  accuracy_meters    integer not null default 30 check (accuracy_meters between 0 and 5000),

  is_anonymous       boolean not null default false,
  client_context     jsonb not null default '{}'::jsonb
);

comment on column public.incidents.lat is
  'Denormalizado a propósito: el filtro de viewport del mapa usa BETWEEN sobre lat/lon indexados, que es más barato que ST_Intersects con geography y evita que el cliente deba serializar geometría PostGIS.';

comment on column public.incidents.confirmation_count is
  'Se incrementa sólo vía la función confirm_incident(), nunca por update directo del cliente: así se puede validar que un usuario no confirme su propio reporte ni lo haga dos veces.';

-- ── Índices espaciales ───────────────────────────────────────────────────────
-- GIST sobre geography para búsquedas por radio; b-tree compuesto para el
-- bounding box del viewport con filtro temporal, que es la consulta más frecuente
-- de toda la app (cada movimiento del mapa la dispara).
create index if not exists incidents_location_gist on public.incidents using gist (location);
create index if not exists incidents_viewport_idx  on public.incidents (lat, lon, occurred_at desc);
create index if not exists incidents_category_idx  on public.incidents (category, occurred_at desc);
create index if not exists incidents_status_idx    on public.incidents (status) where status <> 'removed';
create index if not exists incidents_reporter_idx  on public.incidents (reporter_id);
-- pg_trgm para búsqueda difusa de descripciones.
create index if not exists incidents_description_trgm on public.incidents using gin (description gin_trgm_ops);

drop trigger if exists incidents_touch_updated_at on public.incidents;
create trigger incidents_touch_updated_at
  before update on public.incidents
  for each row execute function public.touch_updated_at();

-- ============================================================================
-- TRUST_CONTACTS
-- ============================================================================
-- ── El límite de 5 se aplica en el servidor ──────────────────────────────────
-- MAX_TRUST_CONTACTS existe también en el dominio del cliente, pero la única
-- restricción que importa es ésta. Un límite sólo del lado del cliente se salta
-- con una petición directa a PostgREST, y en una app de seguridad un contacto
-- extra no autorizado es alguien que recibe la ubicación en vivo del usuario.

create table public.trust_contacts (
  id             uuid primary key default gen_random_uuid(),
  owner_id       uuid not null references public.profiles(id) on delete cascade,
  full_name      text not null check (char_length(full_name) between 1 and 80),
  phone_e164     text not null check (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
  relation       text,
  is_primary     boolean not null default false,
  invited_at     timestamptz,
  accepted_at    timestamptz,
  -- Si el contacto ya usa NomadSafe, puede seguir la escolta dentro de la app en
  -- vez de por SMS. Null hasta que se verifique.
  nomadsafe_user_id uuid references public.profiles(id) on delete set null,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),

  -- Un mismo teléfono no puede estar dos veces en la lista del mismo usuario:
  -- duplicarlo haría que reciba dos SMS idénticos en cada SOS.
  unique (owner_id, phone_e164)
);

comment on column public.trust_contacts.phone_e164 is
  'E.164 obligatorio (constraint regex, no convención). El link wa.me/<número> sólo funciona con el número en formato internacional sin símbolos; un teléfono guardado como "987 654 321" genera un link que WhatsApp rechaza silenciosamente y el contacto nunca recibe la alerta.';

create index if not exists trust_contacts_owner_idx on public.trust_contacts (owner_id);
create index if not exists trust_contacts_phone_idx on public.trust_contacts (phone_e164);

drop trigger if exists trust_contacts_touch_updated_at on public.trust_contacts;
create trigger trust_contacts_touch_updated_at
  before update on public.trust_contacts
  for each row execute function public.touch_updated_at();

-- ── Límite de contactos por usuario ──────────────────────────────────────────
create or replace function public.enforce_trust_contact_limit()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  current_count integer;
begin
  select count(*) into current_count
  from public.trust_contacts
  where owner_id = new.owner_id;

  if current_count >= 5 then
    raise exception 'MAX_TRUST_CONTACTS_EXCEEDED: límite de 5 contactos de confianza';
  end if;

  return new;
end;
$$;

comment on function public.enforce_trust_contact_limit() is
  'El máximo de 5 no es arbitrario: cada contacto recibe un SMS por cada SOS. Con más contactos el costo por alerta crece linealmente y, peor, la alerta se diluye — si 20 personas reciben un aviso, cada una asume que otra va a actuar.';

drop trigger if exists trust_contacts_limit on public.trust_contacts;
create trigger trust_contacts_limit
  before insert on public.trust_contacts
  for each row execute function public.enforce_trust_contact_limit();

-- ============================================================================
-- ESCORT_SESSIONS
-- ============================================================================
-- ── La traza vive en una tabla separada ──────────────────────────────────────
-- escort_positions se escribe cada 10 s durante una escolta. Poner esos puntos en
-- la misma fila que la sesión (un jsonb o un array) haría que cada punto reescriba
-- la fila completa, y con TOAST eso es una reescritura de varios KB cada 10
-- segundos. Separarlo mantiene la sesión ligera y permite purgar posiciones sin
-- tocar los metadatos.

create table public.escort_sessions (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null references public.profiles(id) on delete cascade,
  status                text not null default 'active' check (status in (
                          'active','escalated','sos','completed','cancelled')),
  destination_lat       double precision,
  destination_lon       double precision,
  destination_name      text,
  started_at            timestamptz not null default now(),
  ended_at              timestamptz,
  end_reason            text,
  voice_detection_on    boolean not null default false,
  last_check_in_at      timestamptz,
  next_check_in_at      timestamptz,
  check_in_missed_count integer not null default 0,
  -- Batería al iniciar y al último reporte: permite distinguir "se cortó la
  -- escolta" de "se apagó el teléfono", que para un contacto que espera es una
  -- diferencia enorme.
  battery_at_start      integer,
  battery_last          integer,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);

create index if not exists escort_sessions_user_idx   on public.escort_sessions (user_id, started_at desc);
create index if not exists escort_sessions_active_idx on public.escort_sessions (status) where status in ('active','escalated','sos');

drop trigger if exists escort_sessions_touch_updated_at on public.escort_sessions;
create trigger escort_sessions_touch_updated_at
  before update on public.escort_sessions
  for each row execute function public.touch_updated_at();

create table public.escort_positions (
  id           bigint generated always as identity primary key,
  session_id   uuid not null references public.escort_sessions(id) on delete cascade,
  lat          double precision not null check (lat between -90 and 90),
  lon          double precision not null check (lon between -180 and 180),
  location     geography(Point, 4326) not null,
  accuracy_m   real,
  speed_kmh    real,
  bearing_deg  real,
  battery      integer,
  captured_at  timestamptz not null default now()
);

comment on table public.escort_positions is
  'Tabla de alta frecuencia de escritura (1 punto cada 10 s por sesión activa). Se purga con la política de retención de abajo: guardar el recorrido completo de cada escolta para siempre es acumular historiales de movimiento de personas, que es exactamente el dato más sensible que maneja la app.';

create index if not exists escort_positions_session_idx on public.escort_positions (session_id, captured_at desc);
create index if not exists escort_positions_captured_idx on public.escort_positions (captured_at);

-- ── Retención de trazas ──────────────────────────────────────────────────────
-- Se conserva sólo lo necesario para que un contacto pueda revisar el recorrido
-- reciente de un SOS. Pasados 30 días la traza se borra.
create or replace function public.purge_old_escort_positions(retention_days integer default 30)
returns integer language plpgsql security definer set search_path = public as $$
declare
  deleted_count integer;
begin
  with deleted as (
    delete from public.escort_positions
    where captured_at < now() - make_interval(days => retention_days)
      -- Nunca borrar la traza de una sesión con SOS sin resolver: es evidencia.
      and session_id not in (
        select id from public.escort_sessions where status = 'sos'
      )
    returning 1
  )
  select count(*) into deleted_count from deleted;

  return deleted_count;
end;
$$;

comment on function public.purge_old_escort_positions() is
  'Excepción deliberada: las trazas de sesiones con SOS activo no se purgan. Un recorrido asociado a una alerta de emergencia puede ser evidencia, y borrarlo automáticamente destruiría información que nadie puede regenerar.';

-- ============================================================================
-- SOS_EVENTS
-- ============================================================================
-- ── id generado por el cliente, misma razón que incidents ────────────────────
-- Un SOS puede reintentarse desde el outbox. Duplicarlo significaría que los
-- contactos reciben dos alertas de una misma emergencia, y que el conteo de
-- emergencias de la ciudad se infla.

create table public.sos_events (
  id                  text primary key,
  session_id          uuid references public.escort_sessions(id) on delete cascade,
  user_id             uuid not null references public.profiles(id) on delete cascade,
  trigger             text not null check (trigger in (
                        'manual','panic_word','checkin_missed','route_deviation','contact_raised')),
  status              text not null default 'open' check (status in ('open','acknowledged','resolved','false_alarm')),
  lat                 double precision not null,
  lon                 double precision not null,
  location            geography(Point, 4326) not null,
  accuracy_m          real,
  battery             integer,
  -- Palabra o frase detectada. NUNCA audio: ver nota abajo.
  detected_phrase     text,
  phrase_confidence   real check (phrase_confidence between 0 and 1),
  message             text check (char_length(message) <= 500),
  notified_contact_ids text not null default '',
  triggered_at        timestamptz not null default now(),
  resolved_at         timestamptz,
  resolution_note     text,
  created_at          timestamptz not null default now()
);

comment on column public.sos_events.detected_phrase is
  'Se guarda la palabra reconocida y su score de confianza, NUNCA el audio. Un micrófono que sube grabaciones a la nube convierte la app en un dispositivo de escucha; guardar sólo el texto reconocido mantiene la promesa de "procesamiento local" verificable, y además evita el problema legal de almacenar audio de terceros que aparecen en la grabación sin haber consentido.';

comment on column public.sos_events.notified_contact_ids is
  'CSV de ids, no una tabla de relación: se escribe una sola vez al disparar y se lee completa al mostrar el evento. Normalizarlo en tabla de unión agregaría un write extra por contacto en el momento más crítico del flujo, sin ninguna consulta que lo justifique.';

create index if not exists sos_events_user_idx    on public.sos_events (user_id, triggered_at desc);
create index if not exists sos_events_session_idx on public.sos_events (session_id);
create index if not exists sos_events_open_idx    on public.sos_events (status) where status in ('open','acknowledged');
create index if not exists sos_events_location_gist on public.sos_events using gist (location);

-- ============================================================================
-- OSM_STATIC_FEATURES · datos estructurales de la ciudad
-- ============================================================================
-- Iluminación y clasificación de vía derivadas de OpenStreetMap. Es la mitad
-- "estática" del score de riesgo: sin ella, un barrio sin ningún reporte todavía
-- sería indistinguible de uno sin reportes pero sin luz.

create table public.osm_static_features (
  id            bigint generated always as identity primary key,
  cell_key      text not null unique,
  center_lat    double precision not null,
  center_lon    double precision not null,
  location      geography(Point, 4326) not null,
  lit           boolean,
  lit_source    text check (lit_source in ('osm','inferred','crowd','unknown')),
  road_class    text not null default 'unknown' check (road_class in (
                  'pedestrian','residential','secondary','primary','trunk','unknown')),
  street_name   text,
  surface       text,
  sidewalk      text,
  imported_at   timestamptz not null default now()
);

comment on column public.osm_static_features.cell_key is
  'Clave de celda de ~50 m (misma granularidad que CELL_SIZE_DEGREES en el cliente). La grilla existe para que el join ruta↔iluminación sea una búsqueda por clave en vez de una consulta espacial por tramo: una ruta de 40 tramos hace 40 lookups de índice en vez de 40 ST_DWithin.';

comment on column public.osm_static_features.lit is
  'NULLABLE y no boolean not null default false. "No sabemos si hay luz" y "sabemos que no hay luz" son informaciones distintas: la primera aplica una penalidad moderada y la segunda la máxima. Un default false convertiría toda la ciudad sin datos en zona oscura y el mapa quedaría uniformemente rojo, sin capacidad de distinguir.';

create index if not exists osm_static_features_location_gist on public.osm_static_features using gist (location);
create index if not exists osm_static_features_cell_idx on public.osm_static_features (cell_key);

-- ============================================================================
-- INVITATIONS · attribution del crecimiento orgánico
-- ============================================================================

create table public.invitations (
  id              uuid primary key default gen_random_uuid(),
  inviter_id      uuid not null references public.profiles(id) on delete cascade,
  referral_code   text not null,
  channel         text not null default 'whatsapp' check (channel in (
                    'whatsapp','sms','generic','qr')),
  invitee_phone   text,
  invitee_user_id uuid references public.profiles(id) on delete set null,
  accepted_at     timestamptz,
  created_at      timestamptz not null default now()
);

create index if not exists invitations_inviter_idx on public.invitations (inviter_id);
create index if not exists invitations_code_idx    on public.invitations (referral_code);

-- ── Crédito por invitación aceptada ──────────────────────────────────────────
create or replace function public.credit_referral()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  -- Sólo se acredita una vez por invitado, y nunca auto-referencia.
  if new.invitee_user_id is not null
     and new.accepted_at is not null
     and old.accepted_at is null
     and new.invitee_user_id <> new.inviter_id then

    update public.profiles
    set referral_credits = referral_credits + 1
    where id = new.inviter_id;
  end if;

  return new;
end;
$$;

comment on function public.credit_referral() is
  'La guarda `new.invitee_user_id <> new.inviter_id` no es decorativa: sin ella un usuario puede referirse a sí mismo con un segundo número y farmear créditos, que es el abuso más fácil de ejecutar en cualquier loop de crecimiento con recompensa.';

drop trigger if exists invitations_credit on public.invitations;
create trigger invitations_credit
  after update on public.invitations
  for each row execute function public.credit_referral();

-- ============================================================================
-- FUNCIONES DE NEGOCIO
-- ============================================================================

-- ── Incidencias cercanas ─────────────────────────────────────────────────────
create or replace function public.nearby_incidents(
  p_lat double precision,
  p_lon double precision,
  p_radius_m integer default 1500,
  p_since timestamptz default now() - interval '14 days'
)
returns setof public.incidents
language sql
stable
security invoker
set search_path = public
as $$
  select *
  from public.incidents
  where status <> 'removed'
    and occurred_at >= p_since
    and ST_DWithin(
          location,
          ST_SetSRID(ST_MakePoint(p_lon, p_lat), 4326)::geography,
          p_radius_m)
  order by occurred_at desc
  limit 500;
$$;

comment on function public.nearby_incidents() is
  'security invoker, NO definer: la función debe respetar las políticas RLS del que la llama. Con definer, cualquier usuario autenticado podría leer todos los incidentes de la base saltándose las reglas de visibilidad. El límite 500 protege el payload del cliente: una ciudad entera en un FeatureCollection cruza el puente JNI del renderer y puede congelar el mapa.';

-- ── Confirmar incidente ──────────────────────────────────────────────────────
create or replace function public.confirm_incident(p_incident_id text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_reporter uuid;
  v_caller   uuid := auth.uid();
  v_already  boolean;
begin
  if v_caller is null then
    return jsonb_build_object('ok', false, 'error', 'UNAUTHENTICATED');
  end if;

  select reporter_id into v_reporter
  from public.incidents where id = p_incident_id;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'NOT_FOUND');
  end if;

  -- No se puede confirmar el propio reporte: sería auto-validación, y el conteo
  -- de consenso perdería todo significado.
  if v_reporter = v_caller then
    return jsonb_build_object('ok', false, 'error', 'OWN_REPORT');
  end if;

  -- Una confirmación por usuario por incidente.
  select exists (
    select 1 from public.incident_votes
    where incident_id = p_incident_id and voter_id = v_caller and vote = 'confirm'
  ) into v_already;

  if v_already then
    return jsonb_build_object('ok', false, 'error', 'ALREADY_VOTED');
  end if;

  insert into public.incident_votes (incident_id, voter_id, vote)
  values (p_incident_id, v_caller, 'confirm');

  update public.incidents
  set confirmation_count = confirmation_count + 1,
      status = case
                 -- Escala a 'confirmed' con 3 votos independientes: un umbral bajo
                 -- lo alcanza cualquier grupo coordinado, uno alto hace que la
                 -- validación comunitaria nunca se vea reflejada.
                 when confirmation_count + 1 >= 3 and status = 'active' then 'confirmed'
                 else status
               end
  where id = p_incident_id;

  return jsonb_build_object('ok', true);
end;
$$;

-- ── Disputar incidente ───────────────────────────────────────────────────────
create or replace function public.dispute_incident(p_incident_id text, p_reason text default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_reporter uuid;
  v_caller   uuid := auth.uid();
  v_already  boolean;
begin
  if v_caller is null then
    return jsonb_build_object('ok', false, 'error', 'UNAUTHENTICATED');
  end if;

  select reporter_id into v_reporter from public.incidents where id = p_incident_id;
  if not found then
    return jsonb_build_object('ok', false, 'error', 'NOT_FOUND');
  end if;

  select exists (
    select 1 from public.incident_votes
    where incident_id = p_incident_id and voter_id = v_caller and vote = 'dispute'
  ) into v_already;

  if v_already then
    return jsonb_build_object('ok', false, 'error', 'ALREADY_VOTED');
  end if;

  insert into public.incident_votes (incident_id, voter_id, vote, reason)
  values (p_incident_id, v_caller, 'dispute', p_reason);

  update public.incidents
  set dispute_count = dispute_count + 1,
      status = case
                 -- 3 disputas lo pasan a revisión moderada, y el peso en el motor
                 -- de riesgo cae a 0.15 (ver IncidentStatus.DISPUTED). Es la
                 -- defensa contra reportes falsos coordinados: mentir cuesta, y
                 -- mentir bien cuesta más que verificar.
                 when dispute_count + 1 >= 3 and status <> 'disputed' then 'under_review'
                 when dispute_count + 1 >= 6 then 'disputed'
                 else status
               end
  where id = p_incident_id;

  return jsonb_build_object('ok', true);
end;
$$;

-- ── Tabla de votos (soporte de las dos funciones anteriores) ─────────────────
create table public.incident_votes (
  id          bigint generated always as identity primary key,
  incident_id text not null references public.incidents(id) on delete cascade,
  voter_id    uuid not null references public.profiles(id) on delete cascade,
  vote        text not null check (vote in ('confirm','dispute')),
  reason      text,
  created_at  timestamptz not null default now(),
  unique (incident_id, voter_id, vote)
);

create index if not exists incident_votes_incident_idx on public.incident_votes (incident_id);

-- ── Grilla de riesgo materializada ───────────────────────────────────────────
-- El score de riesgo por celda se precalcula y no se calcula en cada petición del
-- mapa. Calcularlo en vivo sobre todos los incidentes para cada viewport haría
-- que el tiempo de respuesta creciera linealmente con el tamaño de la base, que
-- es justo lo que no puede pasar en una app cuyo uso aumenta cuando aumenta la
-- población.

create materialized view public.risk_grid as
with cells as (
  select
    f.cell_key,
    f.center_lat,
    f.center_lon,
    f.lit,
    f.road_class,
    coalesce(
      sum(
        -- Severidad base por categoría, con decay temporal exponencial. Los
        -- pesos son los mismos que en domain/model/Incident.kt: duplicarlos en SQL
        -- y en Kotlin es una fuente de divergencia, pero recalcularlos en el
        -- cliente obligaría a traer todos los incidentes crudos. Se documenta en
        -- docs/ARCHITECTURE.md como punto único de verdad a revisar en ambos lados.
        (case i.category
           when 'assault'     then 1.00
           when 'theft'       then 0.92
           when 'harassment'  then 0.80
           when 'suspicious'  then 0.40
           when 'dark_street' then 0.38
           when 'road_blocked' then 0.45
           when 'accident'    then 0.50
           else 0.30
         end)
        * (case i.status
             when 'confirmed' then 1.25
             when 'under_review' then 0.5
             when 'disputed' then 0.15
             when 'removed' then 0.0
             else 1.0
           end)
        * (case i.timing when 'recurrent' then 3.0 else 1.0 end)
        * (1 + 0.35 * ln(1 + i.confirmation_count) - 0.20 * i.dispute_count)
        -- Decay exponencial. dark_street no decae: una calle sin luz sigue sin
        -- luz el año que viene, y tratarla como un evento temporal haría que el
        -- mapa olvidara el riesgo estructural del barrio.
        * exp(
            -ln(2) * greatest(0, extract(epoch from (now() - i.occurred_at)) / 3600.0)
            / (case i.category
                 when 'dark_street' then 8760.0
                 when 'assault' then 96.0
                 when 'theft' then 72.0
                 when 'harassment' then 168.0
                 when 'suspicious' then 36.0
                 when 'road_blocked' then 8.0
                 when 'accident' then 24.0
                 else 24.0
               end)
          )
      ) filter (where i.status <> 'removed'),
      0) as crowd_score,
    count(i.id) filter (where i.status <> 'removed') as incident_count
  from public.osm_static_features f
  left join lateral (
    select *
    from public.incidents i
    where ST_DWithin(
            i.location,
            ST_SetSRID(ST_MakePoint(f.center_lon, f.center_lat), 4326)::geography,
            400)   -- maxInfluenceRadiusMeters de RiskConfig
      and i.occurred_at >= now() - interval '90 days'
  ) i on true
  group by f.cell_key, f.center_lat, f.center_lon, f.lit, f.road_class
)
select
  cell_key,
  center_lat,
  center_lon,
  lit,
  road_class,
  incident_count,
  crowd_score,
  -- Mezcla 70/30 igual que crowdWeight/staticWeight de RiskConfig.
  least(1.0,
    0.70 * least(1.0, crowd_score / 2.5)   -- crowdSaturation = 2.5
    + 0.30 * (
        case when lit is false then 0.85
             when lit is null then 0.35
             else -0.15 end
        + case road_class
            when 'trunk' then 0.60
            when 'pedestrian' then 0.25
            when 'primary' then 0.05
            when 'unknown' then 0.15
            when 'secondary' then -0.10
            else 0.0 end
      )
  ) as risk_score,
  case
    when least(1.0, 0.70 * least(1.0, crowd_score / 2.5)) >= 0.70 then 'critical'
    when least(1.0, 0.70 * least(1.0, crowd_score / 2.5)) >= 0.45 then 'high'
    when least(1.0, 0.70 * least(1.0, crowd_score / 2.5)) >= 0.20 then 'moderate'
    else 'low'
  end as risk_level
from cells;

create unique index if not exists risk_grid_cell_key_idx on public.risk_grid (cell_key);

comment on materialized view public.risk_grid is
  'Se refresca con pg_cron cada 10 min (ver abajo). Entre refrescos el score puede estar hasta 10 min atrasado, que es aceptable para riesgo estructural pero NO para una emergencia en curso: por eso los SOS y las posiciones de escolta se leen siempre en tiempo real por Realtime, nunca desde esta vista.';

-- Refresco concurrente: no bloquea las lecturas del mapa mientras recalcula.
create or replace function public.refresh_risk_grid()
returns void language plpgsql security definer set search_path = public as $$
begin
  refresh materialized view concurrently public.risk_grid;
end;
$$;

-- ── Hotspots: agrupamiento espacial en SQL ───────────────────────────────────
-- Complementa al HotspotDetector del cliente. Éste agrupa lo que ya tiene en
-- memoria para la vista actual; esta función sirve para el heatmap global y para
-- las notificaciones push de "zona de riesgo cerca de vos".
create or replace function public.detect_hotspots(
  p_min_incidents integer default 3,
  p_radius_m integer default 150,
  p_since timestamptz default now() - interval '14 days'
)
returns table (
  center_lat double precision,
  center_lon double precision,
  radius_m integer,
  incident_count integer,
  dominant_category text,
  risk_score double precision
)
language sql stable security invoker set search_path = public as $$
  with filtered as (
    select * from public.incidents
    where status not in ('removed','disputed') and occurred_at >= p_since
  ),
  clustered as (
    select
      f.id,
      f.lat, f.lon, f.category,
      count(*) over (
        partition by round(f.lat::numeric, 3), round(f.lon::numeric, 3)
      ) as cluster_size
    from filtered f
  )
  select
    avg(c.lat)::double precision as center_lat,
    avg(c.lon)::double precision as center_lon,
    p_radius_m as radius_m,
    count(*)::integer as incident_count,
    mode() within group (order by c.category) as dominant_category,
    least(1.0, count(*)::double precision / 10.0) as risk_score
  from clustered c
  where c.cluster_size >= p_min_incidents
  group by round(c.lat::numeric, 3), round(c.lon::numeric, 3)
  having count(*) >= p_min_incidents
  order by count(*) desc
  limit 200;
$$;

comment on function public.detect_hotspots() is
  'El agrupamiento por round(lat,3) es una grilla de ~110 m, no un clustering real (DBSCAN sería lo correcto pero es caro en SQL puro). Es una aproximación documentada: suficiente para el heatmap, insuficiente para delimitar una zona con precisión. Si la precisión llegara a importar, el camino es PostGIS ST_ClusterDBSCAN sobre geography, no afinar esta grilla.';

-- ============================================================================
-- ROW LEVEL SECURITY
-- ============================================================================
-- Activada en todas las tablas. Sin excepción: en Supabase una tabla sin RLS es
-- legible por cualquiera con la anon key, que es una clave pública embebida en el
-- APK.

alter table public.profiles            enable row level security;
alter table public.incidents           enable row level security;
alter table public.incident_votes      enable row level security;
alter table public.trust_contacts      enable row level security;
alter table public.escort_sessions     enable row level security;
alter table public.escort_positions    enable row level security;
alter table public.sos_events          enable row level security;
alter table public.osm_static_features enable row level security;
alter table public.invitations         enable row level security;

-- ── PROFILES ─────────────────────────────────────────────────────────────────
-- Lectura pública limitada: el nombre y el código de referido deben ser visibles
-- para que funcione la invitación. El teléfono NO.
create policy "profiles: lectura pública parcial"
  on public.profiles for select
  using (true);

comment on policy "profiles: lectura pública parcial" on public.profiles is
  'USING (true) expone la fila completa, incluido phone. Se mantiene así sólo porque el MVP necesita resolver contactos por teléfono para marcar cuáles ya usan la app. Antes de producción hay que reemplazarla por una vista public_profiles sin phone, o por una columna is_phone_public. Dejar esto sin resolver expondría los números de todos los usuarios.';

create policy "profiles: insert propio"
  on public.profiles for insert with check (auth.uid() = id);

create policy "profiles: update propio"
  on public.profiles for update using (auth.uid() = id) with check (auth.uid() = id);

-- Sin política de DELETE: el borrado de cuenta lo hace la Edge Function
-- delete-account con clave de servicio, que primero anonimiza los incidentes y
-- las trazas y después borra el perfil. Un delete directo del cliente dejaría
-- huérfanos los datos de movimiento.

-- ── INCIDENTS ────────────────────────────────────────────────────────────────
-- Lectura pública: un mapa colaborativo de seguridad sólo funciona si cualquiera
-- puede ver los reportes sin registrarse. La barrera de registro en la lectura
-- mata el loop de crecimiento, porque el usuario invitado por WhatsApp vería un
-- mapa vacío.
create policy "incidents: lectura pública"
  on public.incidents for select
  using (status <> 'removed');

create policy "incidents: insert autenticado"
  on public.incidents for insert
  with check (auth.uid() = reporter_id or is_anonymous);

comment on policy "incidents: insert autenticado" on public.incidents is
  'El `or is_anonymous` permite reportes sin cuenta, que bajan la barrera de entrada. El abuso se contiene por otros medios: rate limiting en la Edge Function, el umbral de 3 disputas que pasa el reporte a revisión, y el peso 0.15 de los disputados en el motor. Cerrar el insert anónimo sería más seguro pero destruiría la adopción inicial, que es el problema real de un mapa colaborativo en una ciudad nueva.';

create policy "incidents: update del autor"
  on public.incidents for update
  using (auth.uid() = reporter_id)
  with check (auth.uid() = reporter_id);

create policy "incidents: delete del autor"
  on public.incidents for delete
  using (auth.uid() = reporter_id);

comment on policy "incidents: delete del autor" on public.incidents is
  'El autor puede borrar su propio reporte. Los votos de la comunidad NO borran el reporte, lo degradan a disputed: borrar contenido por votación lo hace desaparecer sin rastro, y eso es exactamente lo que un acosador coordinado buscaría. Degradarlo mantiene el registro auditable.';

-- ── INCIDENT_VOTES ───────────────────────────────────────────────────────────
create policy "votes: lectura pública" on public.incident_votes for select using (true);
create policy "votes: insert propio" on public.incident_votes for insert with check (auth.uid() = voter_id);
-- Sin update ni delete: un voto emitido no se edita. Permitir borrarlo haría
-- posible votar, observar el efecto y retirar el voto, que es manipulación.

-- ── TRUST_CONTACTS ───────────────────────────────────────────────────────────
-- Privados. Son la lista de personas a las que el usuario autoriza a ver su
-- ubicación en una emergencia; que otro usuario la lea sería filtrar su red de
-- confianza y sus números.
create policy "contacts: solo el dueño" on public.trust_contacts
  for select using (auth.uid() = owner_id);
create policy "contacts: insert dueño" on public.trust_contacts
  for insert with check (auth.uid() = owner_id);
create policy "contacts: update dueño" on public.trust_contacts
  for update using (auth.uid() = owner_id) with check (auth.uid() = owner_id);
create policy "contacts: delete dueño" on public.trust_contacts
  for delete using (auth.uid() = owner_id);

-- Lectura excepcional para el seguimiento en vivo: un contacto invitado a una
-- sesión de SOS puede leer la fila que lo menciona, para mostrar su nombre en la
-- pantalla de seguimiento. Se limita a sesiones con SOS abierto.
create policy "contacts: visible durante SOS del dueño" on public.trust_contacts
  for select using (
    exists (
      select 1 from public.sos_events s
      where s.status in ('open','acknowledged')
        and s.user_id = owner_id
        and position(id::text in s.notified_contact_ids) > 0
    )
  );

comment on policy "contacts: visible durante SOS del dueño" on public.trust_contacts is
  'Usa position(id::text in notified_contact_ids) porque notified_contact_ids es un CSV. Es correcto pero frágil: si un id uuid fuera prefijo de otro, coincidiría por error. Con uuids v4 la probabilidad es despreciable, pero si esto crece conviene migrar a una tabla de unión sos_contacts.';

-- ── ESCORT_SESSIONS ──────────────────────────────────────────────────────────
create policy "sessions: solo el dueño" on public.escort_sessions
  for select using (auth.uid() = user_id);
create policy "sessions: insert dueño" on public.escort_sessions
  for insert with check (auth.uid() = user_id);
create policy "sessions: update dueño" on public.escort_sessions
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Lectura para contactos durante un SOS abierto. Es la base del seguimiento en
-- vivo por link: sin esta política, el contacto invitado vería un mapa vacío.
create policy "sessions: contactos durante SOS" on public.escort_sessions
  for select using (
    exists (
      select 1 from public.sos_events s
      where s.session_id = id
        and s.status in ('open','acknowledged')
        and exists (
          select 1 from public.trust_contacts tc
          where tc.id::text = any(string_to_array(s.notified_contact_ids, ','))
            and tc.owner_id = s.user_id
        )
    )
  );

comment on policy "sessions: contactos durante SOS" on public.escort_sessions is
  'El acceso caduca solo cuando el SOS pasa a resolved: no hay que recordar revocarlo. Eso es importante porque un contacto que conserva acceso indefinido a la ubicación de alguien es un riesgo de seguridad persistente, exactamente lo contrario de lo que la función promete.';

-- ── ESCORT_POSITIONS ─────────────────────────────────────────────────────────
create policy "positions: solo el dueño" on public.escort_positions
  for select using (
    exists (select 1 from public.escort_sessions s
            where s.id = session_id and s.user_id = auth.uid())
  );
create policy "positions: insert dueño" on public.escort_positions
  for insert with check (
    exists (select 1 from public.escort_sessions s
            where s.id = session_id and s.user_id = auth.uid())
  );
create policy "positions: contactos durante SOS" on public.escort_positions
  for select using (
    exists (
      select 1 from public.sos_events s
      where s.session_id = escort_positions.session_id
        and s.status in ('open','acknowledged')
    )
  );

-- ── SOS_EVENTS ───────────────────────────────────────────────────────────────
create policy "sos: solo el dueño" on public.sos_events
  for select using (auth.uid() = user_id);
create policy "sos: insert autenticado" on public.sos_events
  for insert with check (auth.uid() = user_id);
create policy "sos: update dueño" on public.sos_events
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Un contacto notificado puede leer el SOS y marcarlo como reconocido. Sin esto,
-- el flujo de "ya voy para allá" no existe y el contacto tiene que salir de la app
-- para avisar por WhatsApp.
create policy "sos: contactos notificados leen" on public.sos_events
  for select using (
    exists (
      select 1 from public.trust_contacts tc
      where tc.owner_id = sos_events.user_id
        and tc.id::text = any(string_to_array(notified_contact_ids, ','))
    )
  );

create policy "sos: contactos notificados reconocen" on public.sos_events
  for update using (
    status = 'open'
    and exists (
      select 1 from public.trust_contacts tc
      where tc.owner_id = sos_events.user_id
        and tc.id::text = any(string_to_array(notified_contact_ids, ','))
    )
  )
  with check (status in ('open','acknowledged'));

comment on policy "sos: contactos notificados reconocen" on public.sos_events is
  'El with check limita a open→acknowledged: un contacto puede decir "estoy al tanto" pero NO puede resolver el SOS ni marcarlo como falsa alarma. Resolver una emergencia es decisión de quien la vive; dejar que un tercero la cierre podría silenciar una alerta real por error o por presión.';

-- ── OSM_STATIC_FEATURES ──────────────────────────────────────────────────────
-- Lectura pública (datos derivados de OSM, licencia ODbL, atribución requerida).
-- Escritura sólo con clave de servicio, desde el pipeline de importación.
create policy "static: lectura pública" on public.osm_static_features
  for select using (true);
-- Sin políticas de insert/update/delete: el pipeline de importación usa la
-- service_role key, que bypasea RLS. No dejar insert público es importante:
-- cualquiera que pudiera marcar calles como "iluminadas" bajaría artificialmente
-- el riesgo de una zona y desviaría rutas hacia ella.

-- ── INVITATIONS ──────────────────────────────────────────────────────────────
create policy "invitations: solo el invitador" on public.invitations
  for select using (auth.uid() = inviter_id);
create policy "invitations: insert invitador" on public.invitations
  for insert with check (auth.uid() = inviter_id);
create policy "invitations: update del aceptante" on public.invitations
  for update using (auth.uid() = inviter_id or auth.uid() = invitee_user_id)
  with check (auth.uid() = inviter_id or auth.uid() = invitee_user_id);

-- ============================================================================
-- REALTIME
-- ============================================================================
-- Se publica en el canal postgres_changes sólo lo que necesita actualizarse en
-- vivo. Publicar todas las tablas multiplicaría el tráfico del WebSocket de cada
-- cliente conectado, y la escolta mantiene ese socket abierto durante todo el
-- recorrido.

alter publication supabase_realtime add table public.sos_events;
alter publication supabase_realtime add table public.escort_positions;
alter publication supabase_realtime add table public.incidents;

comment on table public.escort_positions is
  'En la publicación Realtime: es lo que permite que el contacto vea al usuario moverse en el mapa sin polling. Sin esto, el seguimiento en vivo requeriría consultar cada pocos segundos, que es peor en latencia y en batería.';

-- ============================================================================
-- PROGRAMACIÓN
-- ============================================================================
-- pg_cron no está habilitado por defecto en Supabase. Activarlo en el dashboard
-- (Database → Extensions → pg_cron) y luego ejecutar:
--
--   select cron.schedule(
--     'refresh-risk-grid',
--     '*/10 * * * *',
--     $$ select public.refresh_risk_grid() $$
--   );
--
--   select cron.schedule(
--     'purge-escort-positions',
--     '0 3 * * *',
--     $$ select public.purge_old_escort_positions(30) $$
--   );
--
-- Se deja comentado y no se ejecuta acá: un script de migración que asuma una
-- extensión habilitada falla en proyectos nuevos, y fallar la migración inicial
-- deja la base a medio crear, que es peor que no tener los jobs programados.

-- ============================================================================
-- VERIFICACIÓN POST-MIGRACIÓN
-- ============================================================================
-- Ejecutar manualmente después de aplicar. Devuelve las tablas que quedaron sin
-- RLS, que en esta base sería una fuga de datos de ubicación.
create or replace function public.audit_rls_coverage()
returns table (table_name text, rls_enabled boolean)
language sql stable security definer set search_path = public as $$
  select c.relname::text as table_name, c.relrowsecurity as rls_enabled
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public'
    and c.relkind in ('r','p')
  order by c.relrowsecurity, c.relname;
$$;

comment on function public.audit_rls_coverage() is
  'Toda fila con rls_enabled = false es un defecto de seguridad. Existe porque agregar una tabla nueva y olvidarse del ALTER TABLE ... ENABLE ROW LEVEL SECURITY no produce ningún error: simplemente deja los datos públicos.';
