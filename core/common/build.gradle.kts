plugins {
    id("myflix.android.library")
    id("myflix.android.hilt")
}

android {
    namespace = "com.myflix.core.common"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
