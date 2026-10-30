import java.util.Base64

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
        versionCode = 9
        versionName = "0.3.6-pre"
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
            // 固定签名：从环境变量读取（CI 用 GitHub Secrets 注入）。
            // 签名不变才能覆盖安装升级；本地无环境变量时回退 debug 签名便于调试
            val ksB64 = System.getenv("SIGNING_KEYSTORE")
            if (ksB64 != null) {
                val ksFile = File("${System.getenv("SIGNING_TMP_DIR") ?: "."}/release.keystore")
                ksFile.parentFile?.mkdirs()
                ksFile.writeBytes(Base64.getDecoder().decode(ksB64))
                signingConfigs {
                    create("release") {
                        storeFile = ksFile
                        storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                        keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                        keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
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
