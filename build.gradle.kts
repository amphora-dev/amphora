// Top-level build file. Plugin versions are pinned here (apply false);
// convention plugins in build-logic apply them to modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

// Formatting is enforced only over Amphora-authored sources. The ported
// com.winlator.cmod kernel lives under `src/main/java/com/winlator/` and keeps
// upstream's layout: reformatting it would make every future diff against
// WinNative unreadable for no behavioural gain.
// @Composable functions are PascalCase by Compose convention; ktlint cannot infer
// that from the name alone. Also in .editorconfig for IDE runs.
val ktlintRules = mapOf(
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
