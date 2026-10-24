@file:Suppress("UnstableApiUsage")

import org.gradle.authentication.http.BasicAuthentication

/*
 * NomadSafe — configuración de repositorios y módulos.
 *
 * Dos decisiones importantes viven aquí:
 *
 * 1. Repositorio de Mapbox autenticado. Los artefactos de Mapbox Maps SDK v11 NO están en
 *    Maven Central: se descargan del Maven privado de Mapbox, que exige un "secret download
 *    token" distinto del token público que usa la app en runtime. Sin él, la resolución de
 *    dependencias falla con un 401 confuso. Ver docs/SETUP.md.
 *
 * 2. FAIL_ON_PROJECT_REPOS: todos los repositorios se declaran una sola vez aquí y ningún
 *    módulo puede añadir los suyos. Evita el clásico bug de "funciona en mi máquina" cuando
 *    un repositorio se cuela sólo en un módulo.
 */

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// El token puede venir de gradle.properties, de -P en línea de comandos o del entorno.
// Se lee en settings porque la resolución de dependencias ocurre antes que los módulos.
val mapboxDownloadsToken: String = providers
    .gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
    .orElse(providers.environmentVariable("MAPBOX_DOWNLOADS_TOKEN"))
    .getOrElse("")

if (mapboxDownloadsToken.isBlank()) {
    logger.warn(
        """

        ╔══════════════════════════════════════════════════════════════════════╗
        ║  NomadSafe: falta MAPBOX_DOWNLOADS_TOKEN                             ║
        ║                                                                      ║
        ║  La build fallará al resolver com.mapbox.maps:android con un 401.    ║
        ║                                                                      ║
        ║  1. Crea una cuenta en https://account.mapbox.com                    ║
        ║  2. Copia el "Secret access token" (scope: Downloads:Read)           ║
        ║  3. Pégalo en local.properties como MAPBOX_DOWNLOADS_TOKEN=sk.xxx     ║
        ║     o en ~/.gradle/gradle.properties                                 ║
        ║                                                                      ║
        ║  Nota: ese token NUNCA se commitea. Ver docs/SETUP.md                ║
        ╚══════════════════════════════════════════════════════════════════════╝

        """.trimIndent(),
    )
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Maven privado de Mapbox (Maps SDK v11, geojson transitivo).
        maven {
            name = "MapboxReleases"
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            credentials {
                // El usuario es literalmente "mapbox" para todos los clientes;
                // lo que autentica es el secret token.
                username = "mapbox"
                password = mapboxDownloadsToken
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

rootProject.name = "NomadSafe"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
    """
    NomadSafe requiere JDK 17 o superior, pero Gradle está corriendo con JDK ${JavaVersion.current()}.
    Java Home actual: ${System.getProperty("java.home")}

    En Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK.
    """.trimIndent()
}
