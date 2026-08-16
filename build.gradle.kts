plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

subprojects {
    apply(
        plugin =
            rootProject.libs.plugins.ktlint
                .get()
                .pluginId,
    )
    apply(
        plugin =
            rootProject.libs.plugins.detekt
                .get()
                .pluginId,
    )

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(
            rootProject.libs.versions.ktlintTool
                .get(),
        )
        // Generated Room and Compose sources are not ours to format.
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        // Type resolution is skipped: detekt 1.23 embeds the Kotlin 1.9 frontend, which
        // cannot parse this project's Kotlin 2.0 sources for the typed rules.
        parallel = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = JavaVersion.VERSION_17.toString()
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
        }
    }
}

/** One command for everything CI checks, so `check` locally means the same thing. */
tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs ktlint, detekt and Android lint across every module."
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })
    dependsOn(subprojects.map { "${it.path}:detekt" })

    // Android lint belongs here too. It was missing, and because this task is documented
    // as "what CI runs", a green run locally read as a green build - while lintDebug,
    // which CI runs separately and with warningsAsErrors, was never invoked at all. It
    // caught a real error in test code that had already been pushed.
    //
    // Named explicitly rather than mapped over subprojects: lint tasks exist only on
    // Android modules, so the mapped form above would break on a plain Kotlin one.
    dependsOn(":app:lintDebug")
}
