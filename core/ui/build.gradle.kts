plugins {
    id("torfilx.android.library")
    id("torfilx.android.compose")
}

android {
    namespace = "com.torfilx.core.ui"
    buildFeatures { androidResources = true }
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    androidTestImplementation(projects.core.testing)
}
