// Plugin versions are pinned here (apply false); the convention plugins in
// build-logic apply them to modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

// Passed explicitly rather than through .editorconfig discovery, which Spotless
// resolves per file; .editorconfig carries the same values for the IDE.
val ktlintRules =
    mapOf(
        "ktlint_code_style" to "intellij_idea",
        "max_line_length" to "120",
        "ij_kotlin_allow_trailing_comma" to "true",
        "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
        // Composables are PascalCase by convention; tests name what they exercise.
        "ktlint_function_naming_ignore_when_annotated_with" to "Composable,Preview,Test",
    )

spotless {
    kotlin {
        target("**/src/*/kotlin/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
        trimTrailingWhitespace()
        endWithNewline()
    }
}
