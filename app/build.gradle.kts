plugins {
    id("myflix.android.application")
    id("myflix.android.compose")
    id("myflix.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.myflix.tv"

    defaultConfig {
        applicationId = "com.myflix.tv"
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += setOf("en", "he")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // Populated from env for CI; falls back to the debug key for local sideloading.
            val storePath = providers.environmentVariable("MYFLIX_KEYSTORE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("MYFLIX_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("MYFLIX_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("MYFLIX_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (providers.environmentVariable("MYFLIX_KEYSTORE").isPresent) {
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
    implementation(projects.core.network)
    implementation(projects.core.player)

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
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(projects.core.testing)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
}
