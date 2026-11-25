plugins {
    id("com.android.application")
}

android {
    namespace = "org.tizendlna.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.tizendlna.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "1.3.0"

        // Strictly 64-bit ARM64 (Snapdragon 7+ Gen 3 / ARMv9-A Cortex-X4/A720/A520)
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.version",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

// Zero third-party dependencies: 100% pure Android 16 platform SDK + Java 21 stdlib.
dependencies {
}
