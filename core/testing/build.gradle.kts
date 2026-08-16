plugins {
    id("torfilx.android.library")
    id("torfilx.android.compose")
    id("torfilx.android.hilt")
}

android {
    namespace = "com.torfilx.core.testing"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.data)
    api(projects.core.network)
    api(platform(libs.androidx.compose.bom))
    api(libs.junit4)
    api(libs.truth)
    api(libs.turbine)
    api(libs.mockk)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.test.core)
    api(libs.androidx.test.ext.junit)
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.hilt.android.testing)
    implementation(libs.kotlinx.datetime)
}
