plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    // Compose Compiler eklentisi
}

android {
    namespace = "com.bysoftware.dronecontroller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bysoftware.dronecontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.0" // Kotlin 2.1.10 ile uyumlu sürüm
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {


    //implementation ("io.mavlink:MAVSDK-Java:1.1.10") // Örnek alternatif
    // implementation("io.mavlink:mavlink:1.1.10") // Resmi MAVLink Java kütüphanesi
    implementation ("io.mavsdk:mavsdk:2.1.0")
    implementation ("io.mavsdk:mavsdk-server:2.1.3")
    implementation ("com.github.mik3y:usb-serial-for-android:3.4.6")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")

    // Asenkron işlemler için
    //implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") // Örnek sürüm, projenize uygun olanı kullanın
    implementation("io.dronefleet.mavlink:mavlink:1.1.11") // Örnek sürüm, güncelini kontrol edin
  //  implementation("io.dronefleet.mavlink:mavlink-common:0.8.0")
// Eğer ArduPilot kullanıyorsanız:
    //implementation("io.dronefleet.mavlink:mavlink-ardupilotmega:0.8.0")
  //  implementation("com.github.felHR85:UsbSerial:6.1.0") // veya kullandığınız güncel versiyon




    // Gerekli diğer bağımlılıklar
    //implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.test.junit4.android)
    implementation(libs.androidx.room.runtime.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}