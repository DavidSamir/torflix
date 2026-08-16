plugins {
    id("myflix.android.library")
    id("myflix.android.hilt")
    id("myflix.android.room")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.myflix.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.network)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(projects.core.testing)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.core)
}
