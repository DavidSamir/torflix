plugins {
    id("torfilx.android.library")
    id("torfilx.android.hilt")
}

android {
    namespace = "com.torfilx.core.common"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
