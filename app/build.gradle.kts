
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.opencode.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.opencode.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.3.9-pre"
        ndk {
            // 只出 64 位包
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 调试包保持不混淆，便于崩溃堆栈定位
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 固定签名：CI 通过 SIGNING_STORE_FILE 传入 keystore 文件路径
            // （workflow 里从 secret 解码到 $RUNNER_TEMP/release.keystore）。
            // 签名不变才能覆盖安装升级；未配置时回退 debug 签名便于本地调试。
            val ksPath = System.getenv("SIGNING_STORE_FILE")
            if (ksPath != null && File(ksPath).exists()) {
                signingConfigs {
                    create("release") {
                        storeFile = File(ksPath)
                        storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                        keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                        keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                        // 同时启用 V1+V2，兼容旧系统安装
                        enableV1Signing = true
                        enableV2Signing = true
                    }
                }
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
