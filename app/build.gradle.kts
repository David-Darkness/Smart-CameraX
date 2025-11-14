plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.david.smartcamerax"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.david.smartcamerax"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // especificar ndkVersion moderno (instalar en SDK Manager)
        ndkVersion = "25.2.9519653"
    }

    // Empaquetado: usar el nuevo manejo de jniLibs para que AGP repackaging alinee correctamente las .so
    packagingOptions {
        jniLibs {
            // false -> usar nuevo empaquetado (no legacy) que repackages native libs
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // CameraX: usar versión 1.5.1
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")

    // ML Kit: actualizar a versiones más recientes
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}