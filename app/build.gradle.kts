plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // Kotlin 2.0 的 Compose 编译器插件
    id("org.jetbrains.kotlin.plugin.serialization")
    // id("com.google.dagger.hilt.android")   // 暂用轻量单例（App.kt），启用时补回 kapt
    // id("com.chaquo.python")                // 需要内置 Python 时启用
}

android {
    namespace = "com.pocketcode.studio"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.pocketcode.studio"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    // 说明：Kotlin 2.0 不再使用 composeOptions.kotlinCompilerExtensionVersion，
    //      Compose 编译器版本由 org.jetbrains.kotlin.plugin.compose 自动对齐。
    compileOptions {
        // sora-editor:language-textmate 要求启用 core library desugaring
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // 代码编辑器（Sora Editor）
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.5")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.5")

    // Root / Shell（libsu）
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:service:5.2.2")

    // 网络 / 序列化
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 依赖注入：暂用 App.kt 内的轻量单例，未启用 Hilt。
    // 启用 Hilt 时取消注释，并在根脚本加回 hilt 插件 + 开启 kapt：
    // implementation("com.google.dagger:hilt-android:2.51.1")
    // kapt("com.google.dagger:hilt-compiler:2.51.1")

    // JS 插件引擎（QuickJS）
    implementation("app.cash.quickjs:quickjs-android:0.9.2")

    // sora-editor 所需的 core library desugaring 运行时
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}