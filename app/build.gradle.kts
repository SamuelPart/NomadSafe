import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * NomadSafe — módulo :app
 *
 * Decisión de modularización: UN solo módulo con layering por paquetes
 * (core/ data/ domain/ feature/). Para un MVP con equipo chico, la
 * multi-modularización compra tiempo de build incremental pero cuesta
 * complejidad de configuración y fricción al mover código entre módulos.
 * La ruta de migración a multi-módulo está documentada en docs/ARCHITECTURE.md
 * y los paquetes ya respetan las fronteras que se volverían módulos.
 */

// local.properties no es visible desde Gradle por defecto: se lee a mano.
// Ninguno de estos valores es un secreto real (ver docs/SECURITY_PRIVACY.md):
//   - la anon key de Supabase es pública por diseño; la seguridad vive en RLS.
//   - el public token de Mapbox es público por diseño; se restringe por paquete+SHA1.
val localProperties = java.util.Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun config(key: String, default: String = ""): String =
    localProperties.getProperty(key)
        ?: providers.gradleProperty(key).getOrElse(default)

val fcmEnabled: Boolean = (findProperty("nomadsafe.enableFcm") as? String)
    ?.toBooleanStrictOrNull() ?: false

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 trae Kotlin integrado: no se aplica org.jetbrains.kotlin.android.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// google-services.json sólo existe cuando hay proyecto Firebase creado.
// Se aplica el plugin de forma condicional para que el repo compile sin credenciales.
if (fcmEnabled) {
    if (!file("google-services.json").exists()) {
        throw GradleException(
            "nomadsafe.enableFcm=true pero falta app/google-services.json. " +
                "Descargalo de Firebase Console > Project settings > Your apps, " +
                "o volvé a poner nomadsafe.enableFcm=false en gradle.properties.",
        )
    }
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.nomadsafe.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nomadsafe.app"
        // 26 = íconos adaptativos en XML puro, java.time nativo, sin desugaring.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── Configuración inyectada en tiempo de compilación ──────────────────
        buildConfigField("String", "SUPABASE_URL", "\"${config("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${config("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "MAPBOX_PUBLIC_TOKEN", "\"${config("MAPBOX_PUBLIC_TOKEN")}\"")
        buildConfigField("boolean", "FCM_ENABLED", "$fcmEnabled")
        buildConfigField(
            "boolean",
            "BUNDLE_VOSK_MODEL",
            "${(findProperty("nomadsafe.bundleVoskModel") as? String)?.toBooleanStrictOrNull() ?: false}",
        )
        // Ciudad del piloto: viewport inicial del mapa y área de features estáticas OSM.
        buildConfigField("String", "PILOT_CITY", "\"${config("NOMADSAFE_PILOT_CITY", "Trujillo")}\"")
        buildConfigField("double", "PILOT_LAT", config("NOMADSAFE_PILOT_LAT", "-8.1116"))
        buildConfigField("double", "PILOT_LON", config("NOMADSAFE_PILOT_LON", "-79.0289"))

        // Idioma por defecto del piloto. Las traducciones viven en values-es / values-en.
        resourceConfigurations += listOf("es", "en")
    }

    buildTypes {
        debug {
            // Sufijo para poder tener debug y release instalados a la vez.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Se firma con la debug key para que cualquiera que clonee el repo pueda
            // armar un release installable. REEMPLAZAR antes de subir a Play Store:
            // ver la sección "Firmado" de docs/SETUP.md.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // ResValues apagado globalmente en gradle.properties; el token de Mapbox se
        // setea por código (MapboxOptions.accessToken) en vez de por recurso.
    }

    packaging {
        resources {
            // JNA (que trae Vosk) y Ktor duplican estos archivos de licencia.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/NOTICE.md",
                "/META-INF/LICENSE.md",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Un abort por lint bloquearía CI en cuanto una librería agregue un check nuevo.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

room {
    // Exporta el esquema JSON de cada versión: requisito para auto-migraciones
    // y para revisar en code review qué cambió en el modelo local.
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    // ── AndroidX núcleo ───────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // ── Compose ───────────────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ── Permisos runtime en Compose ───────────────────────────────────────────
    implementation(libs.accompanist.permissions)

    // ── Hilt ──────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.ext.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.ext.compiler)
    // Requerido por Hilt cuando se procesa con KSP en lugar de kapt.
    ksp(libs.kotlin.metadata.jvm)

    // ── Room (caché local-first y cola de sincronización) ─────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── WorkManager (sincronización diferida del outbox) ──────────────────────
    implementation(libs.androidx.work.runtime.ktx)

    // ── KotlinX ───────────────────────────────────────────────────────────────
    implementation(libs.bundles.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // ── Supabase: Postgres/PostGIS + Auth OTP + Realtime + Storage ────────────
    implementation(platform(libs.supabase.bom))
    implementation(libs.bundles.supabase)
    implementation(libs.ktor.client.cio)

    // ── Mapbox Maps SDK v11 ───────────────────────────────────────────────────
    // Trae com.mapbox.mapboxsdk:mapbox-sdk-geojson como dependencia `api`,
    // así que Point/Feature/FeatureCollection ya están disponibles.
    implementation(libs.mapbox.maps.android)

    // ── Vosk: detección offline de palabras de pánico ─────────────────────────
    // El sufijo @aar replica la declaración del demo oficial de alphacep y evita
    // que Gradle intente resolver JNA como .jar de escritorio (rompe en Android).
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    // ── Ubicación (FusedLocationProviderClient) ───────────────────────────────
    implementation(libs.play.services.location)

    // ── Firebase Cloud Messaging (Fase 2, opt-in) ─────────────────────────────
    if (fcmEnabled) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.messaging)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────
    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
}
