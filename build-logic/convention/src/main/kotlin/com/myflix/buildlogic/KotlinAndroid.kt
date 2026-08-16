package com.myflix.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.version(alias: String): String = libs.findVersion(alias).get().requiredVersion

/**
 * Shared Android + Kotlin configuration applied to every Android module.
 *
 * Targets Fire OS 6+ (API 25). Java/Kotlin target 17 — the build runs on JDK 17 or 21.
 *
 * Note: in AGP 9 `CommonExtension` exposes properties only (the `foo { }` block form lives on the
 * concrete Application/Library extensions), hence the property-style configuration below.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.compileSdk = version("compileSdk").toInt()
    commonExtension.defaultConfig.minSdk = version("minSdk").toInt()

    commonExtension.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    commonExtension.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    // Fire OS 6 is API 25; desugaring back-ports java.time and friends used by our date handling.
    commonExtension.compileOptions.isCoreLibraryDesugaringEnabled = true

    commonExtension.packaging.resources.excludes.addAll(
        listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        ),
    )

    commonExtension.testOptions.unitTests.isIncludeAndroidResources = true
    commonExtension.testOptions.unitTests.isReturnDefaultValues = true

    configureKotlinJvm()

    dependencies.add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:2.1.5")
}

/** Kotlin compiler options shared by Android and pure-JVM modules. */
internal fun Project.configureKotlinJvm() {
    val common: (KotlinCommonCompilerOptions) -> Unit = { options ->
        options.freeCompilerArgs.addAll(
            "-Xconsistent-data-class-copy-visibility",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }

    extensions.findByType(KotlinAndroidProjectExtension::class.java)?.apply {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            common(this)
        }
    }
    extensions.findByType(KotlinJvmProjectExtension::class.java)?.apply {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            common(this)
        }
    }
}

/** Debug helper: prints the extension types registered on a project (used during bring-up). */
internal fun Project.dumpExtensions() {
    extensions.extensionsSchema.elements.forEach { println("EXT ${it.name} -> ${it.publicType}") }
}
