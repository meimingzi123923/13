// 顶层构建脚本：统一锁定工具链版本。
// MiuiX 0.9.4 由 Kotlin 2.4.20 + Compose Multiplatform 1.12.0 + AGP 9.4.1 + Gradle 9.7.1 编译，
// 因此本工程必须整体对齐到同一套工具链，否则会触发「元数据版本过新」编译失败。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
