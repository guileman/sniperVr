import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número de versión automático en CI (GitHub Actions)
val ciBuild = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.guileman.snipervr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guileman.snipervr"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        versionCode = ciBuild
        versionName = "1.0.$ciBuild"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            // En CI se puede reemplazar por un keystore guardado en Secrets (ver README).
            val ks = System.getenv("SIGNING_KEYSTORE_PATH") ?: "${rootDir}/keystore/snipervr.jks"
            storeFile = file(ks)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "snipervr123"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "snipervr"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "snipervr123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "tflite"
    }
}

// Descarga el modelo EfficientDet-Lite0 (COCO) de MediaPipe si no está en assets.
val downloadModel by tasks.registering {
    val out = file("src/main/assets/efficientdet_lite0.tflite")
    outputs.file(out)
    doLast {
        if (!out.exists() || out.length() < 1000) {
            out.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
            logger.lifecycle("Descargando modelo: $url")
            URI(url).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModel) }

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
