plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "cl.radiorecuerdos.retro"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.radiorecuerdos.retro"
        minSdk = 24
        targetSdk = 34
        val build = (project.findProperty("build") as String?)?.toInt() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        create("fija") {
            storeFile = file("firma.jks")
            storePassword = System.getenv("FIRMA_CLAVE") ?: ""
            keyAlias = "radioretro"
            keyPassword = System.getenv("FIRMA_CLAVE") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
