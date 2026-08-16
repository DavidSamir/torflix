plugins {
    id("myflix.android.library")
    id("myflix.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.myflix.core.network"
    buildFeatures { buildConfig = true }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
