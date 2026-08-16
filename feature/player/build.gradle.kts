plugins {
    id("torfilx.android.feature")
}

android {
    namespace = "com.torfilx.feature.player"
}

dependencies {
    implementation(projects.core.player)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.activity.compose)
}
