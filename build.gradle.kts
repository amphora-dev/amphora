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
// Passed explicitly rather than relying on .editorconfig discovery, which Spotless
// resolves per file and silently falls back when it misses. .editorconfig carries the
// same values so IDE-side ktlint agrees.
//
// intellij_idea, not ktlint_official: the official style rewrites every signature with
// two or more parameters onto separate lines, which is a lot of vertical noise for
// interfaces like InputSink. gradle.properties already declares
// kotlin.code.style=official.
val ktlintRules = mapOf(
    "ktlint_code_style" to "intellij_idea",
    "max_line_length" to "120",
    // @Composable functions are PascalCase by Compose convention, and tests name
    // themselves after what they exercise; ktlint infers neither from the name.
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
