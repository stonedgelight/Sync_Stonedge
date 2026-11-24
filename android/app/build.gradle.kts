plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pt.andrecarmo.syncstonedge"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.andrecarmo.syncstonedge"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
    }

    // Chave de assinatura partilhada (debug) para que cada build do CI possa
    // ser instalada por cima da anterior com "adb install -r".
    // Para uma chave privada, definir SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD /
    // SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD no ambiente de build.
    signingConfigs {
        create("shared") {
            val custom = System.getenv("SIGNING_STORE_FILE")
            storeFile = if (custom != null) file(custom) else rootProject.file("keystore/sync_stonedge_debug.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "syncstonedge"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "syncstonedge"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "syncstonedge"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:2.0.13")
}
