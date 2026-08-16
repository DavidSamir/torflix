plugins {
    id("torfilx.android.feature")
}

android {
    namespace = "com.torfilx.feature.settings"
}

dependencies {
    implementation(projects.core.network)
}
