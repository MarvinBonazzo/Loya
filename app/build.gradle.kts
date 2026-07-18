import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.isFile) {
        releaseSigningFile.inputStream().use(::load)
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!getProperty(key).isNullOrBlank()) {
                "keystore.properties is present but required property '$key' is missing"
            }
        }
    }
}

android {
    namespace = "com.cartawallet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cartawallet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "3.2.0"
    }

    flavorDimensions += "scanner"
    productFlavors {
        create("offline") {
            dimension = "scanner"
        }
        create("google") {
            dimension = "scanner"
        }
    }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // OpenCV contiene codice nativo: gli APK separati evitano di installare
    // anche le librerie destinate ad architetture diverse dal telefono.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
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
    val cameraXVersion = "1.6.1"

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.webkit:webkit:1.14.0")

    // Variante Offline: elaborazione locale senza Google Play Services o ML Kit.
    add("offlineImplementation", "androidx.exifinterface:exifinterface:1.4.2")
    add("offlineImplementation", "com.google.zxing:core:3.5.4")
    add("offlineImplementation", "org.opencv:opencv:4.13.0")

    // Variante Google: CameraX + modello barcode incluso, scanner documenti via Play Services.
    add("googleImplementation", "androidx.camera:camera-core:$cameraXVersion")
    add("googleImplementation", "androidx.camera:camera-camera2:$cameraXVersion")
    add("googleImplementation", "androidx.camera:camera-lifecycle:$cameraXVersion")
    add("googleImplementation", "androidx.camera:camera-view:$cameraXVersion")
    add("googleImplementation", "com.google.mlkit:barcode-scanning:17.3.0")
    add("googleImplementation", "com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
}
