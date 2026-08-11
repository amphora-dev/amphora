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

abstract class AggregateJvmCoverageTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reports: ConfigurableFileCollection

    @get:OutputFile
    abstract val summaryFile: RegularFileProperty

    @TaskAction
    fun aggregate() {
        val counterTypes = listOf("INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS")
        val scopeOrder = listOf("Amphora authored", "Ported Winlator", "Generated", "Other", "All measured")
        val totals =
            scopeOrder.associateWith {
                counterTypes.associateWith { longArrayOf(0L, 0L) }.toMutableMap()
            }
        val reportFiles = reports.files.sortedBy { it.absolutePath }
        check(reportFiles.isNotEmpty()) { "No JVM coverage reports were configured" }
        reportFiles.forEach { report ->
            check(report.isFile) { "Missing JVM coverage report: $report" }
            val parserFactory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            val root = parserFactory.newDocumentBuilder().parse(report).documentElement
            val classes = root.getElementsByTagName("class")
            for (classIndex in 0 until classes.length) {
                val classNode = classes.item(classIndex)
                val className = classNode.attributes.getNamedItem("name").nodeValue
                val scope =
                    when {
                        isGeneratedClass(className) -> "Generated"
                        className.startsWith("app/amphora/") -> "Amphora authored"
                        className.startsWith("com/winlator/") -> "Ported Winlator"
                        else -> "Other"
                    }
                val counters = classNode.childNodes
                for (counterIndex in 0 until counters.length) {
                    val counter = counters.item(counterIndex)
                    if (counter.nodeName != "counter") continue
                    val attributes = counter.attributes
                    val type = attributes.getNamedItem("type").nodeValue
                    val missed = attributes.getNamedItem("missed").nodeValue.toLong()
                    val covered = attributes.getNamedItem("covered").nodeValue.toLong()
                    listOf(scope, "All measured").forEach { targetScope ->
                        val total = totals.getValue(targetScope)[type] ?: return@forEach
                        total[0] += missed
                        total[1] += covered
                    }
                }
            }
        }
        val summary =
            buildString {
                appendLine("JVM coverage across ${reportFiles.size} tested Android modules")
                appendLine(
                    "Generated code is reported separately; Compose-transformed authored methods remain authored.",
                )
                scopeOrder.forEach { scope ->
                    val scopeTotals = totals.getValue(scope)
                    val measuredClasses = scopeTotals.getValue("CLASS").sum()
                    if (scope != "All measured" && measuredClasses == 0L) return@forEach
                    appendLine()
                    appendLine(scope)
                    counterTypes.forEach { type ->
                        val (missed, covered) = scopeTotals.getValue(type)
                        val percentage =
                            if (missed + covered == 0L) 0.0 else covered * 100.0 / (missed + covered)
                        appendLine(
                            "  %-11s %6.2f%% (%d/%d)".format(
                                java.util.Locale.ROOT,
                                type.lowercase().replaceFirstChar(Char::uppercase),
                                percentage,
                                covered,
                                missed + covered,
                            ),
                        )
                    }
                }
            }
        val output = summaryFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(summary)
        logger.lifecycle("\n$summary")
    }

    private fun isGeneratedClass(className: String): Boolean {
        val simpleName = className.substringAfterLast('/')
        return simpleName == "BuildConfig" ||
            simpleName == "R" ||
            simpleName.startsWith("R$") ||
            simpleName.startsWith("Hilt_") ||
            simpleName.startsWith("Dagger") ||
            simpleName.startsWith("ComposableSingletons$") ||
            "_HiltModules" in simpleName ||
            "_Factory" in simpleName ||
            "_MembersInjector" in simpleName ||
            "_GeneratedInjector" in simpleName ||
            "_ComponentTreeDeps" in simpleName
    }
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

// A stable repository-wide JVM test entry point. Android creates
// testDebugUnitTest for every module, including modules with no test sources;
// invoking that task name from the root therefore schedules empty module tasks.
// Keep the aggregate limited to modules that actually contain JVM tests and
// include the convention-plugin tests from the composite build.
val androidProjectsWithJvmTests =
    subprojects.filter { project ->
        project.fileTree("src/test") {
            include("**/*.java", "**/*.kt")
        }.files.isNotEmpty()
    }

val androidJvmTestTasks =
    androidProjectsWithJvmTests.map { project -> "${project.path}:testDebugUnitTest" }

tasks.register("jvmTest") {
    group = "verification"
    description = "Run all repository JVM tests, including build-logic tests."
    dependsOn(androidJvmTestTasks)
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}

tasks.register<AggregateJvmCoverageTask>("jvmCoverage") {
    group = "verification"
    description = "Run JVM tests and aggregate scoped JaCoCo counters for authored, ported, and generated code."
    val coverageTasks = androidProjectsWithJvmTests.map { "${it.path}:createDebugUnitTestCoverageReport" }
    dependsOn(coverageTasks)
    reports.from(
        androidProjectsWithJvmTests.map {
            it.layout.buildDirectory.file("reports/coverage/test/debug/report.xml")
        },
    )
    summaryFile.set(layout.buildDirectory.file("reports/coverage/jvm-summary.txt"))
}
