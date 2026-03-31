// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

tasks.register("assembleDebug") {
    group = "build"
    description = "Assembles the standard debug app variant."
    dependsOn(":app:assembleStandardDebug")
}

tasks.register("installDebug") {
    group = "install"
    description = "Installs the standard debug app variant."
    dependsOn(":app:installStandardDebug")
}

tasks.register("uninstallDebug") {
    group = "install"
    description = "Uninstalls the standard debug app variant."
    dependsOn(":app:uninstallStandardDebug")
}

tasks.register("assembleRelease") {
    group = "build"
    description = "Assembles the standard release app variant."
    dependsOn(":app:assembleRelease")
}

tasks.register("bundleRelease") {
    group = "build"
    description = "Bundles the standard release app variant."
    dependsOn(":app:bundleStandardRelease")
}
