plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aura.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aura.launcher"
        minSdk = 28 // Android 9
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.0"

        val storeUrl = (project.findProperty("storeUrl") as String?) ?: ""
        buildConfigField("String", "STORE_URL", "\"$storeUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 方便直接侧载安装;正式分发请换成你自己的签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // 每种 CPU 单独出一个安装包(每个都带自己的浏览器内核,体积各约 80MB)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // 影视播放:只要 ExoPlayer 内核,控制界面自己用 Compose 画(不引入 media3-ui)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // 内置浏览器内核(GeckoView):老电视的系统 WebView 太旧时,用它打开央视官方直播页
    val gecko = "159.0.20261102144711"
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$gecko")
    implementation("org.mozilla.geckoview:geckoview-armeabi-v7a:$gecko")
    implementation("org.mozilla.geckoview:geckoview-x86_64:$gecko")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 启动加速:安装后按下面的 baseline-prof.txt 预编译常用代码
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
