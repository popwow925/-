// EririLily game runtime: Zalith Launcher 2's native runtime (libpojavexec, GPL-3.0) and renderers,
// plus EririLily's thin launch layer on top.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.eririlily.runtime"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            ndkBuild { abiFilters += listOf("arm64-v8a") }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    buildFeatures { prefab = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources {
        noCompress += listOf("xz", "jar", "zip")
    }
}

dependencies {
    implementation("com.bytedance:bytehook:1.0.10")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
