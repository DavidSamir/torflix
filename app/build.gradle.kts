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
        versionCode = 9
        versionName = "0.1.9"
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
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck")
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
