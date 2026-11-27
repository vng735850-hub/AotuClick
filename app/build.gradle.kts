plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autoclick"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.autoclick"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.2"
    }
    // Khoá ký cố định: mọi bản build (kể cả trên GitHub Actions) cùng một chữ ký,
    // nên cài đè bản mới lên bản cũ được mà không bị "Ứng dụng chưa được cài đặt".
    signingConfigs {
        getByName("debug") {
            storeFile = file("nvpklick.keystore")
            storePassword = "nvpklick123"
            keyAlias = "nvpklick"
            keyPassword = "nvpklick123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
