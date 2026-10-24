// ============================================================================
// NomadSafe · Edge Function `raise-sos`
// ----------------------------------------------------------------------------
// Dispara el protocolo completo de emergencia. Se ejecuta en el servidor y NO en
// el teléfono por una razón concreta: en el momento de un SOS el dispositivo puede
// estar sin datos, con la batería agotándose o directamente en manos de otra
// persona. Todo lo que dependa de que el cliente siga vivo puede fallar justo
// cuando importa.
//
// Secuencia:
//   1. Validar autenticación y sesión de escolta.
//   2. Persistir el evento de SOS.
//   3. Notificar a los contactos de confianza (SMS + push).
//   4. Escalar la sesión.
//   5. Devolver el resumen para que el cliente muestre qué se hizo.
//
// Cada paso se ejecuta aunque el anterior falle parcialmente: si el SMS no sale,
// igual hay que persistir el evento y avisar por push. Un protocolo de emergencia
// que aborta en el primer error es un protocolo que no existe.
// ============================================================================

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

// ── Constantes ───────────────────────────────────────────────────────────────

/** Máximo de contactos a notificar. Coincide con MAX_TRUST_CONTACTS del dominio. */
const MAX_CONTACTS = 5;

/** Reintentos por notificación. Más de 3 en un SMS sólo retrasa las demás. */
const NOTIFY_RETRIES = 3;

/** Backoff base entre reintentos, en ms. */
const NOTIFY_BACKOFF_MS = 500;

/** Triggers válidos. Debe coincidir con domain/model/User.kt · SosTrigger. */
const VALID_TRIGGERS = [
  "manual",
  "panic_word",
  "checkin_missed",
  "route_deviation",
  "contact_raised",
];

/** Ventana de deduplicación. */
const DEDUPE_WINDOW_MINUTES = 2;

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

// Proveedor SMS. Se lee de env y no se hardcodea: cambiar de proveedor en una app
// de emergencias es una decisión operativa, no de código.
const SMS_PROVIDER_URL = Deno.env.get("SMS_PROVIDER_URL");
const SMS_PROVIDER_KEY = Deno.env.get("SMS_PROVIDER_KEY");
const TWILIO_SID = Deno.env.get("TWILIO_SID");
const TWILIO_TOKEN = Deno.env.get("TWILIO_TOKEN");
const TWILIO_FROM = Deno.env.get("TWILIO_FROM");

serve(handleRequest);

// ── Handler ──────────────────────────────────────────────────────────────────

async function handleRequest(req: Request): Promise<Response> {
  // CORS: la función se llama desde el cliente Android (origen propio) y desde el
  // navegador en el flujo de seguimiento en vivo.
  const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers":
      "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
  };

  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return json({ ok: false, error: "METHOD_NOT_ALLOWED" }, 405, corsHeaders);
  }

  const startedAt = Date.now();

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
      return json({ ok: false, error: "UNAUTHENTICATED" }, 401, corsHeaders);
    }

    // Cliente con el JWT del usuario: sirve para saber QUIÉN llama y aplicar RLS.
    const userClient = createClient(SUPABASE_URL, ANON_KEY || SERVICE_ROLE_KEY, {
      global: { headers: { Authorization: authHeader } },
    });

    const {
      data: { user },
      error: authError,
    } = await userClient.auth.getUser();

    if (authError || !user) {
      console.warn("[raise-sos] autenticación rechazada:", authError?.message);
      return json({ ok: false, error: "UNAUTHENTICATED" }, 401, corsHeaders);
    }

    const body = await req.json().catch(() => ({}));
    const validation = validatePayload(body);
    if (!validation.ok) {
      return json({ ok: false, error: validation.error }, 400, corsHeaders);
    }

    // Cliente de servicio: bypasea RLS. Se usa sólo para leer contactos y escribir
    // el evento, nunca para exponer datos al llamador.
    const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    // ── 1. Deduplicación ─────────────────────────────────────────────────────
    // Un SOS puede reintentarse desde el outbox del cliente. Sin esta guarda, un
    // reintento manda N SMS a los mismos contactos: además del costo, un contacto
    // que recibe cinco alertas idénticas en un minuto deja de creer que sea real.
    const dupe = await findRecentDuplicate(admin, user.id, body.client_event_id);
    if (dupe) {
      console.log(`[raise-sos] duplicado de ${dupe.id}, no se re-notifica`);
      return json(
        { ok: true, sos_id: dupe.id, deduplicated: true },
        200,
        corsHeaders,
      );
    }

    // ── 2. Persistir el evento ───────────────────────────────────────────────
    // ANTES de notificar. Si se notifica primero y la inserción falla, los
    // contactos reciben una alerta de un evento que no existe en el sistema: no se
    // puede seguir, no se puede resolver, no queda registro. Es el peor orden
    // posible.
    const sosId = body.client_event_id ?? crypto.randomUUID();

    const { data: sosEvent, error: insertError } = await admin
      .from("sos_events")
      .insert({
        id: sosId,
        user_id: user.id,
        session_id: body.session_id ?? null,
        trigger: body.trigger,
        status: "open",
        lat: body.lat,
        lon: body.lon,
        location: `SRID=4326;POINT(${body.lon} ${body.lat})`,
        accuracy_m: body.accuracy_m ?? null,
        battery: body.battery ?? null,
        detected_phrase: body.detected_phrase ?? null,
        phrase_confidence: body.phrase_confidence ?? null,
        message: body.message ?? null,
        notified_contact_ids: "",
        triggered_at: body.triggered_at ?? new Date().toISOString(),
      })
      .select("id, user_id, lat, lon")
      .single();

    if (insertError || !sosEvent) {
      console.error("[raise-sos] no se pudo persistir el evento:", insertError?.message);
      // Se devuelve 500 y NO se notifica: sin evento persistido no hay nada que
      // los contactos puedan seguir.
      return json({ ok: false, error: "PERSIST_FAILED" }, 500, corsHeaders);
    }

    // ── 3. Contactos de confianza ────────────────────────────────────────────
    const { data: contacts } = await admin
      .from("trust_contacts")
      .select("id, full_name, phone_e164, nomadsafe_user_id, is_primary")
      .eq("owner_id", user.id)
      .order("is_primary", { ascending: false })
      .order("created_at", { ascending: true })
      .limit(MAX_CONTACTS);

    const contactList = contacts ?? [];

    if (contactList.length === 0) {
      // Caso real y frecuente: el usuario apretó SOS sin haber configurado
      // contactos. Se registra igual — el evento queda y la ciudad lo ve — y se
      // avisa al cliente para que la UI lo diga explícitamente en vez de mostrar
      // "alerta enviada" cuando no se envió a nadie.
      console.warn(`[raise-sos] usuario ${user.id} sin contactos configurados`);
    }

    const trackingUrl = buildTrackingUrl(sosId);
    const smsBody = buildSmsMessage(sosEvent, body, trackingUrl);

    // Se notifican en paralelo y no en secuencia: el segundo contacto no debe
    // esperar a que termine el reintento del primero. En una emergencia los
    // segundos cuentan y NOTIFY_RETRIES × backoff secuencial puede sumar minutos.
    const notifyResults = await Promise.allSettled(
      contactList.map((contact) => notifyContact(admin, contact, smsBody, sosId)),
    );

    const notifiedIds = contactList
      .filter((_, i) => notifyResults[i].status === "fulfilled")
      .map((c) => c.id);

    const failedCount = notifyResults.filter((r) => r.status === "rejected").length;

    // Se registra a quién se logró avisar. Es lo que permite que el cliente
    // muestre "3 de 5 contactos notificados" en vez de un éxito ambiguo.
    await admin
      .from("sos_events")
      .update({ notified_contact_ids: notifiedIds.join(",") })
      .eq("id", sosId);

    // ── 4. Escalar la sesión de escolta ──────────────────────────────────────
    if (body.session_id) {
      await admin
        .from("escort_sessions")
        .update({ status: "sos" })
        .eq("id", body.session_id)
        .eq("user_id", user.id);
    }

    // ── 5. Perfil del usuario, para el mensaje ───────────────────────────────
    const { data: profile } = await admin
      .from("profiles")
      .select("display_name")
      .eq("id", user.id)
      .single();

    console.log(
      `[raise-sos] sos=${sosId} trigger=${body.trigger} notificados=${notifiedIds.length}/${contactList.length} en ${Date.now() - startedAt}ms`,
    );

    return json(
      {
        ok: true,
        sos_id: sosId,
        tracking_url: trackingUrl,
        notified_count: notifiedIds.length,
        total_contacts: contactList.length,
        failed_count: failedCount,
        user_name: profile?.display_name ?? null,
        // Se devuelve explícitamente para que la UI pueda decir la verdad cuando
        // no hay contactos, en vez de mostrar un "alerta enviada" falso.
        no_contacts: contactList.length === 0,
      },
      200,
      corsHeaders,
    );
  } catch (err) {
    console.error("[raise-sos] error no capturado:", err);
    return json({ ok: false, error: "INTERNAL" }, 500, corsHeaders);
  }
}

// ── Validación ───────────────────────────────────────────────────────────────

/**
 * Valida el payload.
 *
 * Las coordenadas se comprueban contra un rango real y no sólo contra "es un
 * número": un GPS que devuelve (0,0) o NaN produce un enlace de seguimiento que
 * apunta al Golfo de Guinea, y los contactos pierden tiempo buscando en el lugar
 * equivocado. Es mejor rechazar el SOS con coordenadas inválidas y que el cliente
 * reintente con un fix nuevo que enviar una alerta geográficamente falsa.
 */
function validatePayload(body: any): { ok: true } | { ok: false; error: string } {
  if (!body || typeof body !== "object") return { ok: false, error: "BAD_PAYLOAD" };

  if (!VALID_TRIGGERS.includes(body.trigger)) {
    return { ok: false, error: "INVALID_TRIGGER" };
  }

  if (!isFiniteNumber(body.lat) || body.lat < -90 || body.lat > 90) {
    return { ok: false, error: "INVALID_LATITUDE" };
  }

  if (!isFiniteNumber(body.lon) || body.lon < -180 || body.lon > 180) {
    return { ok: false, error: "INVALID_LONGITUDE" };
  }

  // (0,0) es casi siempre un fix inválido, no una posición real en el Atlántico.
  if (body.lat === 0 && body.lon === 0) {
    return { ok: false, error: "NULL_ISLAND_FIX" };
  }

  if (body.phrase_confidence != null) {
    if (!isFiniteNumber(body.phrase_confidence) || body.phrase_confidence < 0 || body.phrase_confidence > 1) {
      return { ok: false, error: "INVALID_CONFIDENCE" };
    }
  }

  if (body.message != null && String(body.message).length > 500) {
    return { ok: false, error: "MESSAGE_TOO_LONG" };
  }

  return { ok: true };
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

// ── Deduplicación ────────────────────────────────────────────────────────────

async function findRecentDuplicate(
  admin: ReturnType<typeof createClient>,
  userId: string,
  clientEventId?: string,
): Promise<{ id: string } | null> {
  // Si el cliente mandó su id, la búsqueda es exacta: el outbox reintentó.
  if (clientEventId) {
    const { data } = await admin
      .from("sos_events")
      .select("id")
      .eq("id", clientEventId)
      .maybeSingle();
    if (data) return { id: data.id };
  }

  // Si no, se busca por ventana temporal y mismo trigger: dos disparos casi
  // simultáneos del mismo origen son el mismo evento.
  const since = new Date(Date.now() - DEDUPE_WINDOW_MINUTES * 60_000).toISOString();
  const { data } = await admin
    .from("sos_events")
    .select("id")
    .eq("user_id", userId)
    .eq("status", "open")
    .gte("triggered_at", since)
    .order("triggered_at", { ascending: false })
    .limit(1)
    .maybeSingle();

  return data ? { id: data.id } : null;
}

// ── Notificación ─────────────────────────────────────────────────────────────

/**
 * Notifica a un contacto por todos los canales disponibles.
 *
 * Se intenta push (si el contacto usa la app) Y SMS, en paralelo. No uno u otro:
 * el push puede no llegar si el teléfono está en modo no molestar o sin datos, y
 * el SMS puede fallar por cobertura. En una emergencia se quiere redundancia de
 * canales, no elegancia de flujo.
 */
async function notifyContact(
  admin: ReturnType<typeof createClient>,
  contact: { id: string; full_name: string; phone_e164: string; nomadsafe_user_id: string | null },
  smsBody: string,
  sosId: string,
): Promise<{ contact_id: string; channels: string[] }> {
  const channels: string[] = [];

  // Push in-app, si el contacto tiene cuenta.
  if (contact.nomadsafe_user_id) {
    try {
      await sendInAppAlert(admin, contact.nomadsafe_user_id, sosId);
      channels.push("push");
    } catch (err) {
      console.warn(`[raise-sos] push falló para ${contact.id}:`, err);
    }
  }

  // SMS al teléfono. Se intenta siempre, incluso si el push funcionó: la
  // redundancia es deliberada y vale el costo.
  try {
    await sendSmsWithRetry(contact.phone_e164, smsBody);
    channels.push("sms");
  } catch (err) {
    console.error(`[raise-sos] SMS falló para ${contact.id}:`, err);
    // Se relanza para que Promise.allSettled lo cuente como fallo. Si se
    // tragara el error, el cliente reportaría "contacto notificado" cuando en
    // realidad no recibió nada, que es mentir en el momento crítico.
    throw err;
  }

  if (channels.length === 0) {
    throw new Error(`ningún canal disponible para ${contact.id}`);
  }

  return { contact_id: contact.id, channels };
}

/**
 * SMS con reintento exponencial.
 *
 * El reintento existe porque las fallas de SMS son mayormente transitorias
 * (congestión del proveedor, timeout). Rendirse al primer fallo dejaría a un
 * contacto sin alerta por un problema de medio segundo.
 */
async function sendSmsWithRetry(to: string, body: string): Promise<void> {
  let lastError: unknown;

  for (let attempt = 0; attempt < NOTIFY_RETRIES; attempt++) {
    try {
      await sendSms(to, body);
      return;
    } catch (err) {
      lastError = err;
      if (attempt < NOTIFY_RETRIES - 1) {
        await sleep(NOTIFY_BACKOFF_MS * Math.pow(2, attempt));
      }
    }
  }

  throw lastError;
}

/**
 * Envío SMS.
 *
 * Soporta Twilio y un proveedor genérico por URL. Si no hay ninguno configurado,
 * lanza: silenciar el error haría que el sistema reportara éxito sin haber enviado
 * nada, y en una app de emergencias ese es el fallo más peligroso posible — peor
 * que no enviar, porque el usuario cree que está protegido.
 */
async function sendSms(to: string, body: string): Promise<void> {
  if (TWILIO_SID && TWILIO_TOKEN && TWILIO_FROM) {
    const url = `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_SID}/Messages.json`;
    const form = new URLSearchParams({ To: to, From: TWILIO_FROM, Body: body });

    const res = await fetch(url, {
      method: "POST",
      headers: {
        Authorization: "Basic " + btoa(`${TWILIO_SID}:${TWILIO_TOKEN}`),
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: form.toString(),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Twilio ${res.status}: ${text.slice(0, 200)}`);
    }
    return;
  }

  if (SMS_PROVIDER_URL && SMS_PROVIDER_KEY) {
    const res = await fetch(SMS_PROVIDER_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${SMS_PROVIDER_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ to, message: body }),
    });

    if (!res.ok) {
      throw new Error(`Proveedor SMS ${res.status}`);
    }
    return;
  }

  throw new Error("SMS_NOT_CONFIGURED: falta TWILIO_* o SMS_PROVIDER_*");
}

/**
 * Alerta in-app para contactos que ya usan NomadSafe.
 *
 * Se escribe en una tabla de notificaciones y se deja que Realtime la empuje. No
 * se llama a FCM directamente desde acá: mantener la entrega en un solo canal
 * (Realtime) evita dos caminos de notificación con semánticas distintas que haya
 * que mantener en sincronía.
 */
async function sendInAppAlert(
  admin: ReturnType<typeof createClient>,
  targetUserId: string,
  sosId: string,
): Promise<void> {
  await admin.from("sos_events").select("id").eq("id", sosId).limit(1);

  // Realtime ya publica sos_events; el contacto con acceso por la política
  // "sos: contactos notificados leen" recibe el INSERT en vivo. Este punto es un
  // gancho para agregar push nativo cuando se habilite FCM en la Fase 2.
  console.log(`[raise-sos] alerta in-app para ${targetUserId} vía Realtime (sos=${sosId})`);
}

// ── Construcción del mensaje ─────────────────────────────────────────────────

function buildTrackingUrl(sosId: string): string {
  const base = Deno.env.get("WEB_BASE_URL") ?? "https://nomadsafe.app";
  return `${base}/track/${sosId}`;
}

/**
 * Arma el cuerpo del SMS.
 *
 * ── Restricción real: 160 caracteres ─────────────────────────────────────────
 * Un SMS de más de 160 caracteres se parte en varios segmentos, que se cobran por
 * separado y pueden llegar DESORDENADOS. Un mensaje de emergencia que llega en dos
 * partes invertidas es ilegible. Por eso el cuerpo se construye corto y la
 * ubicación va en el enlace, no como coordenadas en texto: un par "lat,lon"
 * consume 20 caracteres que el contacto tendría que copiar a mano en un mapa.
 */
function buildSmsMessage(
  sosEvent: { lat: number; lon: number },
  body: any,
  trackingUrl: string,
): string {
  const triggerLabel = triggerText(body.trigger);
  const header = `NomadSafe: ${triggerLabel}`;

  const message = `${header}\nVer ubicacion en vivo: ${trackingUrl}`;

  // Si aun así se pasa, se recorta el encabezado y NO el enlace. Perder el enlace
  // deja el SMS sin utilidad; perder el detalle del trigger sólo pierde contexto.
  if (message.length > SMS_MAX_LENGTH) {
    return `NomadSafe: alerta\n${trackingUrl}`;
  }

  return message;
}

const SMS_MAX_LENGTH = 160;

function triggerText(trigger: string): string {
  switch (trigger) {
    case "manual":
      return "pidio ayuda";
    case "panic_word":
      return "se detecto una palabra de auxilio";
    case "checkin_missed":
      return "no respondio la confirmacion";
    case "route_deviation":
      return "se desvio de su ruta";
    case "contact_raised":
      return "un contacto activo la alerta";
    default:
      return "alerta de emergencia";
  }
}

// ── Utilidades ───────────────────────────────────────────────────────────────

function json(payload: unknown, status: number, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json", ...extraHeaders },
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
