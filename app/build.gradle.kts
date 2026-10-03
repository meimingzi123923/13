import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pocketcode.studio"
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.pocketcode.studio"
        minSdk = 26
        // 目标 28：Android 10+ 对 targetSdk>=29 的应用启用 W^X，禁止执行应用私有目录中的
        // 可执行文件；沙盒内置的 proot / 语言运行时必须在私有目录执行，故沿用 Termux 的做法。
        targetSdk = 28
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

    compileOptions {
        // sora-editor 需要 core library desugaring
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 已移除 android.kotlinOptions，改用 Kotlin 扩展的 compilerOptions。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---------- UI 框架：小米 MiuiX 0.9.4（Compose Multiplatform 组件库） ----------
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")

    // 图标（Compose 官方扩展图标，与 MiuiX 无冲突）
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Activity / Lifecycle（与 MiuiX 0.9.4 对齐）
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // 代码编辑器（Sora Editor）
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.5")

    // Root / Shell（libsu）
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:service:5.2.2")

    // 网络 / 序列化 / 协程
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // JS 插件引擎（QuickJS）
    implementation("app.cash.quickjs:quickjs-android:0.9.2")

    // core library desugaring 运行时
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // tar.gz 解包（运行时释放内置 Alpine 根文件系统）
    implementation("org.apache.commons:commons-compress:1.27.1")
}