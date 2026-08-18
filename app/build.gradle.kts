plugins {
    id("torfilx.android.application")
    id("torfilx.android.compose")
    id("torfilx.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.torfilx.tv"

    defaultConfig {
        applicationId = "com.torfilx.tv"
        versionCode = 10
        versionName = "0.2.0"
        testInstrumentationRunner = "com.torfilx.tv.HiltTestRunner"
        resourceConfigurations += setOf("en")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // v1 (JAR) signing is required by Android 6 and older — Fire OS 5 sticks reject a v2-only
        // APK with "There was a problem parsing the package".
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            // Populated from env for CI; falls back to the debug key for local sideloading.
            val storePath = providers.environmentVariable("TORFILX_KEYSTORE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("TORFILX_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TORFILX_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TORFILX_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8/minification is deliberately OFF. ART on Fire OS 5 (Android 5.1) miscompiles R8's
            // optimised dex and the process dies with a native SIGSEGV on a coroutine thread at
            // launch; the same build with minification off runs clean. Since the whole point is to
            // support the Fire OS 5 sticks, correctness wins over the smaller/obfuscated APK. The
            // proguard-rules.pro keep-rules are retained but inactive — see the note in that file.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Every Fire TV is 32-bit ARM (armeabi-v7a); arm64 is cheap forward-insurance for a
            // 64-bit Android TV box. The x86/x86_64 libtorrent binaries exist only for the emulator
            // and are ~13 MB of dead weight in a shipped APK, so keep them out of release. The debug
            // variant keeps all ABIs so it still runs on the x86 emulators used for testing.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

            signingConfig = if (providers.environmentVariable("TORFILX_KEYSTORE").isPresent) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // ChromeOsAbiSupport wants an x86_64 ABI so the app runs on ChromeOS; this is a Fire TV app
        // (32-bit ARM only), and shipping x86 is exactly the bloat #15 removed, so the check does
        // not apply.
        //
        // IconMissingDensityFolder wants drawable-mdpi/hdpi buckets; those are phone densities. Fire
        // TV renders at xhdpi (1080p) and xxhdpi/xxxhdpi (4K), which are the only buckets shipped, so
        // the "missing" folders are intentional. This check is folder-level and its baseline
        // fingerprint does not match across OSes (generated on Windows, CI runs on Linux), so it
        // leaked past the baseline and, under warningsAsErrors, failed the Linux CI lint. Disabling
        // it is the correct call for a TV-only app rather than shipping unused phone-density assets.
        disable += setOf(
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            "ObsoleteLintCustomCheck", "ChromeOsAbiSupport", "IconMissingDensityFolder",
        )
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.player)
    implementation(projects.core.torrent)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    implementation(projects.feature.home)
    implementation(projects.feature.library)
    implementation(projects.feature.details)
    implementation(projects.feature.search)
    implementation(projects.feature.player)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp.core)

    testImplementation(projects.core.testing)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.rules)
    kspAndroidTest(libs.hilt.compiler)
}
