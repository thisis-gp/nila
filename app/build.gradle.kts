plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nila.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nila.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.composables:icons-lucide-android:2.2.1")
    implementation("com.composables:icons-font-awesome-brands-android:2.2.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Swiggy MCP (OAuth 2.1 + JSON-RPC) and OSM Maps (Nominatim/OSRM) network clients.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.browser:browser:1.9.0")
    // Food/restaurant images from Swiggy's CDN inside NILA's own cards.
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
