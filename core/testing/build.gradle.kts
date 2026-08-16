plugins {
    id("myflix.android.library")
    id("myflix.android.compose")
    id("myflix.android.hilt")
}

android {
    namespace = "com.myflix.core.testing"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.data)
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
