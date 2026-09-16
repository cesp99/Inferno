// Top-level build file: plugin versions live in gradle/libs.versions.toml.
// Kotlin is built into AGP 9; org.jetbrains.kotlin.android is deliberately NOT applied anywhere.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
