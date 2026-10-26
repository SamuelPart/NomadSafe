@file:Suppress("UnstableApiUsage")

import org.gradle.authentication.http.BasicAuthentication

/*
 * NomadSafe — configuración de repositorios y módulos.
 *
 * Dos decisiones importantes viven aquí:
 *
 * 1. FAIL_ON_PROJECT_REPOS: todos los repositorios se declaran una sola vez aquí y ningún
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
        // Sin repositorios privados: todo el stack de mapa es open source
        // (MapLibre en Maven Central + tiles raster de OpenStreetMap), así que
        // un clone fresco del repo sincroniza sin configurar ninguna credencial.
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
