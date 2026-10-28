plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// arm64-v8a for phones; x86_64 only for emulator test builds (-PelAbi=x86_64)
val elAbi = (findProperty("elAbi") as String?) ?: "arm64-v8a"

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
            abiFilters += listOf(elAbi)
        }
        // emulator test builds (x86_64) can be inspected over WebView DevTools; phone builds cannot
        buildConfigField("boolean", "TEST_BUILD", (elAbi != "arm64-v8a").toString())
    }

    signingConfigs {
        // CI signs with a release key when the secrets exist; otherwise the debug key
        create("release") {
            val ks = System.getenv("EL_KEYSTORE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("EL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EL_KEY_ALIAS")
                keyPassword = System.getenv("EL_KEY_PASSWORD")
            } else {
                // test builds: a fixed key in the repo, so each test APK installs over the last one
                storeFile = file("el-test.jks")
                storePassword = "eririlily-test"
                keyAlias = "el-test"
                keyPassword = "eririlily-test"
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


    packaging {
        // the core binary and the game's native libraries must exist as real files
        // in nativeLibraryDir (they are exec'd / dlopen'ed by path)
        jniLibs.useLegacyPackaging = true
        // the runtime module and the prebuilt renderer/LWJGL packages each bring these
        jniLibs.pickFirsts += listOf("**/libc++_shared.so", "**/libbytehook.so")
    }

    androidResources {
        noCompress += listOf("xz", "jar", "zip")
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
    // game runtime: Zalith Launcher 2's native layer + renderers, EririLily's launch code (GPL-3.0)
    implementation(project(":runtime"))
    // prebuilt renderers / LWJGL natives / OpenAL / SDL from Zalith Launcher 2
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
