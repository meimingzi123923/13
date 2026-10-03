# 08 · 如何生成可安装的 APK

> 目标：把本工程编译成 `app-debug.apk`，安装到安卓手机。
> 前提认知：**APK 必须在 x86_64 电脑 / 云端编译**，手机本地无法产出（原因见文末）。

---

## 方案一：GitHub Actions 云构建（推荐，无需电脑）⭐

已内置工作流：`.github/workflows/build-apk.yml`。

1. 在 GitHub 新建一个仓库（私有即可）。
2. 把整个 `PocketCodeStudio` 目录推上去：
   ```bash
   cd PocketCodeStudio
   git init && git add .
   git commit -m "init: PocketCodeStudio skeleton"
   git branch -M main
   git remote add origin https://github.com/<你的账号>/<仓库名>.git
   git push -u origin main
   ```
3. 推送后自动开始构建；也可在仓库 **Actions → Build APK → Run workflow** 手动触发。
4. 构建完成后（约 2~5 分钟），在该次运行的 **Artifacts** 区下载
   `PocketCodeStudio-debug-apk.zip`，解压得到 `app-debug.apk`。
5. 传到手机点击安装（需在系统设置里允许「未知来源应用」）。

> 首次推送后若云端编译报错，按日志定位，通常对应 `docs/07` 残余风险清单里的条目。

---

## 方案二：本地电脑 + Android Studio（最稳，便于调试）

1. 安装 Android Studio（自带 JDK 17 与 SDK Manager）。
2. `File → Open` 打开工程根目录。
3. SDK Manager 里装 Platform 34、Build-Tools 34、**NDK 26.1.10909125**、CMake 3.22.1。
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`。
5. 产物在 `app/build/outputs/apk/debug/app-debug.apk`，可直接连手机安装。

命令行等价写法：
```bash
chmod +x gradlew
./gradlew assembleDebug
```

---

## 方案三：Termux（进阶，仅适合能折腾的用户）

Termux 里可装 `openjdk-17` 与 `aapt2`（有 arm64 版），理论上能跑 Gradle。
**但有一个致命卡点**：官方 NDK（clang 交叉编译器）**只提供 x86_64 主机版**，
arm64 手机上无法用它编译 `app/src/main/cpp/term.cpp`（终端原生库）。

绕行办法：用 Termux 自带的 clang 手工把 `term.cpp` 编成
`libpcs_term.so`，放进 `app/src/main/jniLibs/arm64-v8a/`，并临时移除
`app/build.gradle.kts` 里的 `externalNativeBuild` 配置，再 `./gradlew assembleDebug`。
步骤繁琐、易踩坑，**不推荐**，仅供了解。

---

## 为什么手机本身造不出 APK？（技术原因，备查）

1. **工具链缺失**：JDK / Android SDK / NDK 均未安装，且体积达数 GB。
2. **架构不匹配**：官方 `aapt2`、`d8`、NDK 的 `clang` 均为 **x86_64 Linux 二进制**，
   而手机是 **arm64**；本 App 内置的运行时只做文件系统/权限隔离，
   **不做 CPU 指令翻译**，因此这些 x86_64 二进制根本无法执行（会报
   `cannot execute binary file`）。工具链必须与设备 ABI 一致（如 aarch64）。
3. **结论**：Android 应用的「首次构建」必然发生在一台 x86_64 主机或云端 runner 上。
   上述方案一正是用 GitHub 的免费 x86_64 runner 替你完成这一步。