plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.aichat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aichat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("AI_CHAT_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
        versionName = System.getenv("AI_CHAT_VERSION_NAME") ?: "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 固定签名：同一把 key 签出来的包才能互相覆盖安装。
    // CI runner 每次都是全新环境，用自动生成的 debug keystore 会导致每次签名不同。
    signingConfigs {
        create("aichat") {
            storeFile = file("../keystore/aichat.jks")
            storePassword = "aichat2026"
            keyAlias = "aichat"
            keyPassword = "aichat2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aichat")
        }
        debug {
            signingConfig = signingConfigs.getByName("aichat")
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp：流式 SSE 必须用它（HttpURLConnection 的流式读取不可靠）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
