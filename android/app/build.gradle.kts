plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.eririlily.launcher"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "cn.eririlily.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 80
        versionName = "0.8.0-android"
        ndk {
            // The game runtime (JRE, LWJGL natives, renderers) is built for 64-bit ARM.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    signingConfigs {
        // CI signs with a release key when the secrets exist; otherwise the debug key
        create("release") {
            val ks = System.getenv("EL_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("EL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EL_KEY_ALIAS")
                keyPassword = System.getenv("EL_KEY_PASSWORD")
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // the core binary and the game's native libraries must exist as real files
        // in nativeLibraryDir (they are exec'd / dlopen'ed by path)
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        noCompress += listOf("xz", "jar")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}
