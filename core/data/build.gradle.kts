plugins {
    id("torfilx.android.library")
    id("torfilx.android.hilt")
    id("torfilx.android.room")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.torfilx.core.data"
    buildFeatures { androidResources = true }
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
}
