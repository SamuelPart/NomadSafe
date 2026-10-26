# NomadSafe

**Seguridad ciudadana en tiempo real.** Una app Android nativa donde los vecinos reportan incidentes sobre un mapa colaborativo y obtienen rutas que priorizan calles iluminadas y de bajo riesgo, no sólo las más rápidas.

Cuatro capacidades diferenciales:

| | |
|---|---|
| 🗺️ **Rutas seguras** | Puntúa cada tramo combinando reportes de la comunidad con iluminación y tipo de vía de OpenStreetMap. Devuelve la ruta más segura, no la más corta. |
| 🛡️ **Escolta virtual** | Servicio en primer plano que sigue funcionando con la pantalla bloqueada, pide confirmaciones periódicas y escala a alerta si no llegan. |
| 🎙️ **Detección de voz offline** | Vosk procesa el audio localmente y reconoce palabras de pánico (*auxilio*, *ayuda*, *socorro*) para disparar el protocolo en silencio. Ningún audio sale del teléfono. |
| 📈 **Crecimiento orgánico** | Invitación de contactos de confianza por WhatsApp y alertas compartibles con mensaje ya redactado. Cada uso genera alcance nuevo. |

---

## Estado del proyecto

**Scaffold arquitectónico completo, listo para slices verticales.** No es un demo de pantallas: la capa de datos, el motor de riesgo, el servicio de escolta, el pipeline de sincronización offline y el backend Supabase están implementados y conectados.

Implementado:

- ✅ Proyecto Gradle con AGP 9, Kotlin 2.3, KSP, Hilt, Room, Compose
- ✅ **Motor de riesgo** — scoring por tramo con decay temporal, consenso comunitario, iluminación y clase de vía; 35 tests JVM
- ✅ **Modo Escolta** — servicio en primer plano, check-ins adaptativos, detección de desvío, watchdog de batería
- ✅ **Detección de pánico por voz** — Vosk offline en español, matcher con distancia de Levenshtein y gramática acotada
- ✅ **Capa de datos local-first** — Room como fuente de verdad, outbox con backoff exponencial, sincronización por WorkManager
- ✅ **Supabase** — gateway, DTOs, Realtime, Auth por OTP telefónico, Edge Functions
- ✅ **Mapa** — MapLibre (open source, sin token) con heatmap ponderado por severidad, capas conmutables, hotspots
- ✅ **Backend SQL** — migración completa con PostGIS, RLS en todas las tablas, funciones de riesgo y hotspots
- ✅ **Loop de crecimiento** — builder de contenido compartible, códigos de referido, mensajes de SOS

Pendiente (placeholders explícitos, no código roto):

- ⬜ Pantallas de Auth, Onboarding, Contactos y Perfil — las rutas existen en el grafo de navegación
- ⬜ Seguimiento en vivo web para contactos
- ⬜ Pipeline de importación OSM → `osm_static_features`
- ⬜ FCM — diferido tras `PushTransport`, detrás de un flag de build
- ⬜ Tests de integración del repositorio y del servicio de escolta

---

## Por qué estas decisiones

### Supabase en lugar de Firebase

El proyecto arrancó con Firebase en el enunciado y se migró a Supabase. La diferencia que importa acá no es de precio sino de modelo de datos:

- **PostGIS nativo.** La consulta central de la app es "qué incidentes hay en este viewport" y "qué tan peligroso es este tramo". Con Firestore eso se resuelve con geohashes en el cliente; con Postgres es un índice GIST y un `ST_DWithin`.
- **RLS declarativa.** Las reglas de quién puede leer la ubicación de quién están en la base y no repartidas entre el cliente y las Cloud Functions. En una app que maneja posiciones en tiempo real de personas, que la política viva en un solo lugar auditable no es una preferencia.
- **SQL para el motor de riesgo.** La vista materializada `risk_grid` recalcula el score de todas las celdas de la ciudad en una pasada. Expresar eso en reglas de Firestore habría sido imposible.

El costo aceptado: Supabase no tiene un equivalente directo a FCM. Se mantiene Realtime como canal primario (el WebSocket vive en el servicio de escolta) y FCM sólo para despertar un proceso muerto, detrás de `nomadsafe.enableFcm=false` para que el proyecto compile sin `google-services.json`.

### Local-first, no remote-first

La app guarda en Room primero y sincroniza después. No es una optimización de rendimiento:

> Un reporte de un incidente de seguridad se hace frecuentemente desde un lugar con mala señal, o inmediatamente después de huir de uno. Una app que necesita red para guardar el reporte pierde el dato justo cuando más vale.

Consecuencias de diseño:

- El cliente genera los ids (`inc-<uuid>`) antes de enviar. Sin eso, cada reintento del outbox crearía incidentes duplicados que inflarían artificialmente el riesgo de una cuadra.
- El outbox guarda el payload **ya serializado**. Si un reporte encola hace dos semanas y el DTO cambió, el reintento no falla por un schema que ya no existe.
- Las posiciones de escolta en vivo **no** pasan por el outbox. Una posición de hace diez minutos no sirve para nada; un incidente de hace diez minutos sí.

### Vosk y no un servicio de voz en la nube

Dos razones, y la segunda es la que decide:

1. Funciona sin red, que es el escenario real de una emergencia.
2. **El audio nunca sale del teléfono.** Un servicio en la nube convierte la app en un dispositivo que graba y sube audio del entorno, donde hay terceras personas que nunca consintieron. Se guarda la palabra reconocida y su score de confianza, jamás la grabación.

Costo aceptado: ~39 MB de modelo en español que se descargan en primer arranque y no vienen en el APK (`nomadsafe.bundleVoskModel=false`).

### El motor de riesgo reusado en el mapa y en las rutas

La etiqueta de riesgo del mapa y el score que evita tramos al trazar una ruta usan **el mismo** `RiskEngine`. Podrían ser dos fórmulas y daría menos código, pero produciría dos verdades contradictorias en la misma pantalla: el mapa diría "zona tranquila" sobre una cuadra que el motor de rutas se niega a atravesar. El usuario aprendería a desconfiar de ambas.

---

## Empezar

### Requisitos

- **Android Studio** con **JDK 17** o superior (el build lo verifica y falla con un mensaje claro si no)
- **Android SDK 37** (compileSdk) y un dispositivo o emulador con **API 26+**
- Un proyecto en [Supabase](https://supabase.com) — *opcional para arrancar: sin credenciales la app corre en modo demo*

### 1. Configurar credenciales

```bash
cp local.properties.example local.properties
```

Editar `local.properties`:

```properties
SUPABASE_URL=https://tu-proyecto.supabase.co
SUPABASE_ANON_KEY=tu-anon-key
PILOT_CITY=Trujillo
PILOT_LAT=-8.1116
PILOT_LON=-79.0287
```

**Sin credenciales la app igual compila y corre** en modo demo: usa `MockLocationSource` y datos locales. Es deliberado — un clone del repo no debería necesitar cuatro cuentas de servicio para ver la arquitectura funcionar.

El mapa **no lleva credenciales de ningún tipo**: el render es MapLibre (BSD) y los
tiles, routing y geocodificación vienen de servidores públicos de OpenStreetMap
(OSRM y Nominatim). Un clone fresco sincroniza sin configurar nada de eso.

### 2. Backend Supabase

```bash
supabase db push          # aplica supabase/migrations/0001_init.sql
supabase functions deploy raise-sos
```

Después, en el dashboard:

1. **Database → Extensions**: activar `pg_cron`
2. Ejecutar los dos `cron.schedule` que están comentados al final de la migración (refresco de `risk_grid` cada 10 min, purga de trazas a las 03:00)
3. **Authentication → Providers**: activar Phone
4. Configurar las variables de la función:

```bash
supabase secrets set \
  TWILIO_SID=xxx TWILIO_TOKEN=xxx TWILIO_FROM=+1xxx \
  WEB_BASE_URL=https://nomadsafe.app
```

> ⚠️ **Antes de producción:** la política `profiles: lectura pública parcial` expone la columna `phone` de todos los usuarios. Está documentada en la migración como deuda conocida del MVP. Hay que reemplazarla por una vista `public_profiles` sin teléfono.

### 3. Modelo de voz

Se descarga solo en el primer arranque (~39 MB desde `alphacephei.com`) a `filesDir/vosk-model`. Sin red, la detección de pánico se desactiva con un aviso explícito en lugar de fallar en silencio.

### 4. Correr

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest        # 35 tests del motor de riesgo
```

---

## Arquitectura

```
app/src/main/java/com/nomadsafe/app/
├── core/
│   ├── config/         AppConfig — piloto, credenciales, modo demo
│   ├── designsystem/   Tema, tipografía, paleta de riesgo
│   ├── location/       LocationSource (fused / mock), perfiles de consumo
│   ├── navigation/     Grafo de navegación y deep links
│   ├── result/         NsResult — errores tipados, no excepciones
│   └── util/           GeoUtils (haversine, bounding box), dispatchers, NsLog
├── domain/
│   ├── model/          Incident, SafeRoute, RouteSegment, User, SosEvent…
│   └── repository/     Interfaces. El dominio no sabe de Supabase ni de Room.
├── data/
│   ├── local/          Room — 6 entidades, 5 DAOs, esquema exportado
│   ├── remote/         SupabaseGateway (único punto de contacto) + DTOs
│   ├── repository/     Implementaciones local-first
│   └── sync/           OutboxSyncManager, SyncWorker, backoff exponencial
├── feature/
│   ├── risk/           RiskEngine, RiskConfig, HotspotDetector
│   ├── escort/         Servicio en primer plano, StateHolder, SOS, pánico por voz
│   ├── map/            ViewModel, pantalla, capas de MapLibre
│   ├── report/         Formulario de reporte
│   ├── route/          Rutas seguras
│   └── growth/         Contenido compartible, códigos de referido
└── di/                 Módulos de Hilt
```

**Regla de dependencias:** `feature → domain ← data`. Las features nunca importan de `data`, y `domain` no importa de nadie.

**Dos aislamientos deliberados.** Todo el código de Supabase vive en `data/remote/SupabaseGateway.kt` y todo el de MapLibre en `feature/map/MapLayers.kt`. No es limpieza estética: son los dos proveedores con mayor probabilidad de cambio de API, y confinarlos hace que una migración toque un archivo en vez de cuarenta.

### Flujo de un reporte

```
UI → IncidentRepository.reportIncident()
       ├─ Room.insert()                    ← visible al instante, sin red
       ├─ Outbox.enqueue(isCritical=true)  ← sobrevivirá a que muera el proceso
       └─ SupabaseGateway.insert()         ← intento inmediato
              ↓ éxito  → Outbox.markSynced()
              ↓ fallo  → queda encolado; SyncWorker reintenta con backoff
```

### Flujo de un SOS

```
Disparador (botón / palabra de pánico / check-in perdido / desvío)
   → SosCoordinator
       ├─ EscortRepository.raiseSos()      → Room + outbox crítico
       ├─ Edge Function raise-sos          → SMS + push a contactos
       ├─ Notificación local de alta prioridad
       └─ Intent de WhatsApp prellenado
```

El SOS se dispara desde el **servicio**, no desde un ViewModel. Un ViewModel muere con la pantalla; el servicio en primer plano sobrevive a que el usuario cierre la app, que es exactamente lo que pasa cuando alguien entra en pánico y aprieta el botón de inicio.

---

## Modelo de riesgo

Cada tramo de ruta recibe un score `0..1` mezclando dos componentes:

```
score = 0.70 · componente_social + 0.30 · componente_estructural
```

**Componente social** — kernel gaussiano sobre los incidentes cercanos (σ = 90 m, radio de influencia 400 m), ponderado por:

| Factor | Efecto |
|---|---|
| Categoría | `assault` 1.00 → `other` 0.30. `dark_street` es estructural: no decae. |
| Estado | confirmado ×1.25, en revisión ×0.5, **disputado ×0.15** |
| Recurrencia | "pasa seguido" ×3.0 — un patrón importa más que un evento aislado |
| Consenso | `clamp(1 + 0.35·ln(1+confirmaciones) − 0.20·disputas, 0.1, 2.0)` |
| Tiempo | decaimiento exponencial con media vida por categoría |

**Componente estructural** — iluminación y clase de vía desde OSM:

| Iluminación | Penalidad | | Vía | Penalidad |
|---|---|---|---|---|
| sin luz | +0.85 | | troncal | +0.60 |
| desconocida | +0.35 | | peatonal | +0.25 |
| iluminada | −0.15 | | residencial | 0.00 |

> **Por qué la iluminación desconocida penaliza y no se ignora.** Tratar `lit = NULL` como "iluminado" haría que todo barrio sin datos en OSM se viera seguro por omisión, que es exactamente al revés de lo que una app de prevención debería suponer. La penalidad moderada (+0.35 contra +0.85 de la oscuridad confirmada) mantiene la distinción entre *no sabemos* y *sabemos que no hay luz*.

**Piso de riesgo crítico.** Sin él, una ruta de 100 tramos puede diluir una cuadra peligrosísima en un promedio aceptable. Si algún tramo supera 0.60, el score total no puede bajar de:

```
piso = 0.50 + (peor_tramo − 0.60) · 1.0 + fracción_exposición · 0.25
```

La fracción de exposición usa **longitud**, no cantidad: tres cuadras cortas no equivalen a tres avenidas largas.

**Perfiles.** El scoring cambia según contexto: de noche la iluminación pesa más (0.55/0.45) y el bonus por calle iluminada se duplica; en frío (sin datos de la comunidad) se apoya 100% en la componente estructural en vez de devolver "zona tranquila" por ausencia de reportes.

Verificado con 39 casos en un espejo Python del motor (`/tmp/nsverify/risk_reference.py` durante el desarrollo). Los valores de calibración iniciales resultaron inalcanzables — el peor caso realista daba 0.6109 contra un umbral crítico de 0.75, lo que dejaba el piso de riesgo como código muerto. Se recalibró a 0.60/0.50.

---

## Crecimiento orgánico

Tres loops, los tres con el contenido ya redactado para que compartir cueste un toque:

1. **Contactos de confianza → usuarios.** Al configurar la escolta se invita por WhatsApp con un enlace que lleva código de referido. El contacto que entra ya tiene un caso de uso concreto, no una app genérica.
2. **Reportes → alcance.** Cada incidente genera un mensaje compartible con el enlace al mapa. Quien lo recibe ve el mapa **sin registrarse** — cerrar la lectura detrás de auth mataría el loop, porque el invitado vería una pantalla de login en vez del contenido que lo trajo.
3. **SOS → urgencia compartida.** El enlace de seguimiento en vivo se difunde a los contactos, que son precisamente las personas con mayor probabilidad de instalar la app después.

**Anti-abuso.** Los loops de crecimiento con recompensa atraen farming. Defensas implementadas:

- El código de referido se genera en el servidor con garantía de unicidad, no se deriva del id en el backend (el cliente sí lo deriva como fallback offline, para que una reinstalación sin red conserve la atribución).
- `credit_referral()` rechaza la auto-referencia.
- Un usuario no puede confirmar su propio reporte ni votar dos veces lo mismo.
- Tres disputas pasan un reporte a revisión moderada; seis lo marcan como disputado y su peso cae a 0.15. Mentir cuesta, y mentir bien cuesta más que verificar.

---

## Seguridad y privacidad

Decisiones que limitan la funcionalidad a propósito:

- **Nunca se guarda audio.** Sólo la palabra reconocida y su score. Ver *Por qué Vosk*.
- **Trazas de escolta con retención de 30 días**, excepto las de sesiones con SOS sin resolver, que pueden ser evidencia.
- **Precisión de ubicación configurable al reportar.** Desplazar el pin unos metros evita que reportar un incidente en la esquina revele dónde vivís.
- **Contactos privados.** La lista de contactos de confianza no es legible por otros usuarios. Excepción acotada: durante un SOS abierto, los contactos notificados pueden leer la sesión y las posiciones, y ese acceso caduca solo cuando el SOS se resuelve.
- **Un contacto puede marcar "estoy al tanto" pero no puede resolver el SOS.** Cerrar una emergencia es decisión de quien la vive.
- **RLS en todas las tablas**, incluso las de sólo lectura. En Supabase una tabla sin RLS es pública para cualquiera con la `anon key`, que es una clave embebida en el APK.

Auditoría disponible en SQL:

```sql
select * from public.audit_rls_coverage();
-- toda fila con rls_enabled = false es un defecto de seguridad
```

---

## Configuración de build

Flags en `gradle.properties`, los dos en `false` por defecto para que un clone compile sin configuración extra:

| Flag | Efecto |
|---|---|
| `nomadsafe.enableFcm` | Aplica el plugin `google-services` y enlaza FCM. Requiere `google-services.json`. |
| `nomadsafe.bundleVoskModel` | Empaqueta el modelo de Vosk en el APK (+39 MB) en vez de descargarlo. |

El build de release firma con la clave de debug a propósito, para que `assembleRelease` funcione en un clone fresco. **No es apto para publicar** — antes de subir a Play Store hay que configurar un keystore real.

---

## Roadmap

**Fase 1 · Piloto en una ciudad** *(estado actual)*
Scaffold completo, motor de riesgo, escolta, mapa, backend. Faltan las pantallas de auth/onboarding/contactos y el pipeline de importación OSM.

**Fase 2 · Cierre del loop de crecimiento**
Seguimiento en vivo web para contactos, FCM para despertar procesos muertos, notificaciones de zona de riesgo, dashboard de referidos.

**Fase 3 · Datos y precisión**
Importación OSM automatizada, calibración del motor contra datos reales de incidencia, tiles vectoriales desde PostGIS (`ST_AsMVT`) cuando el viewport supere las ~5.000 features.

**Fase 4 · Escala**
Sharding por ciudad, clustering real con `ST_ClusterDBSCAN`, moderación asistida, verificación de reportes por cruce con fuentes públicas.

Detalle en [`docs/ROADMAP.md`](docs/ROADMAP.md). Decisiones técnicas y deuda conocida en [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Licencia

Código propietario. Dependencias principales: MapLibre GL Native (BSD), Vosk (Apache-2.0), Supabase (Apache-2.0), OpenStreetMap (ODbL — **requiere atribución** en cualquier producto derivado de `osm_static_features`; los tiles, routing OSRM y geocodificación Nominatim se usan bajo sus políticas de uso con User-Agent identificable).
