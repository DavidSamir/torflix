plugins {
    id("myflix.android.feature")
}

android {
    namespace = "com.myflix.feature.player"
}

dependencies {
    implementation(projects.core.player)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.activity.compose)
}
