plugins {
    id("torfilx.android.library")
    id("torfilx.android.hilt")
    id("torfilx.android.room")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.torfilx.core.data"
    buildFeatures { androidResources = true }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The exported Room schemas are the fixtures the migration test replays; make them available to
    // the instrumented test as assets.
    sourceSets {
        named("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.torrent)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(projects.core.testing)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
