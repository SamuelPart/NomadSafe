/*
 * Build script raíz.
 *
 * Aquí sólo se declaran los plugins con `apply false` para fijar sus versiones en el
 * classpath de toda la build. Declararlos todos en la raíz evita el problema de que un
 * subproyecto traiga una versión transitiva distinta del mismo plugin.
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.gms) apply false
}
