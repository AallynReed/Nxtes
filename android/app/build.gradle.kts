plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.mildhope.nxtes"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.mildhope.nxtes"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.pdfium)
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.androidx.work)
    implementation(libs.androidx.ink.authoring.compose)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
