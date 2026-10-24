# ─────────────────────────────────────────────────────────────────────────────
# NomadSafe — reglas R8/ProGuard
# ─────────────────────────────────────────────────────────────────────────────
# Cada bloque explica POR QUÉ existe. Regla de la casa: no agregar un -keep
# "por si acaso"; si algo se rompe con minify, se reproduce y se documenta acá.

# ── Vosk / JNA ───────────────────────────────────────────────────────────────
# JNA resuelve nativos y mapea structs por reflexión sobre nombres de clase.
# R8 no puede ver ese uso, así que sin estos keep el reconocedor explota en
# runtime con UnsatisfiedLinkError o NoSuchFieldError, no en compilación.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public *** getByValue(java.lang.String, java.lang.Class);
}
-dontwarn java.awt.*

# ── kotlinx.serialization ────────────────────────────────────────────────────
# Los DTOs se (de)serializan por reflection sobre el serializer generado.
# Sin estos keep, R8 renombra campos y Supabase devuelve nulls silenciosos:
# el bug más difícil de diagnosticar de toda la app, porque no hay crash.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nomadsafe.app.**$$serializer { *; }
-keepclassmembers class com.nomadsafe.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.nomadsafe.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Supabase-kt / Ktor ───────────────────────────────────────────────────────
# Ktor usa ServiceLoader para descubrir engines y plugins.
-keep class * implements io.ktor.client.HttpClientEngine
-keep class * implements io.ktor.client.HttpClientEngineFactory
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# ── Mapbox ───────────────────────────────────────────────────────────────────
# El SDK v11 carga nativos y usa Gson sobre modelos GeoJSON propios.
-keep class com.mapbox.** { *; }
-keep class com.mapbox.geojson.** { *; }
-dontwarn com.mapbox.**

# ── Room ─────────────────────────────────────────────────────────────────────
# Room genera impls en tiempo de compilación (vía KSP), pero las Entity se
# inspeccionan por reflection para el mapeo cursor->objeto.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# ── Modelos de dominio que cruzan la frontera Parcelable/Bundle ──────────────
-keepclassmembers class com.nomadsafe.app.domain.model.** { *; }

# ── Stack traces útiles en Crashlytics/Sentry ────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
