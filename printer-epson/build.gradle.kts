plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.queuekiosk.printer.epson"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":printer-core"))
    // Epson ePOS2 SDK for Android - download from Epson, see README.md. Native libs go in src/main/jniLibs/<abi>/.
    implementation(files("libs/ePOS2.jar"))
}
