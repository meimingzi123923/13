# PocketCode Studio（口袋编码）

> 一个面向 Android 的「类 VSCode」移动端编程 IDE。
> 目标：**界面精简整洁 · 依赖库全面 · 支持插件 · 可试运行/编译 · 可调用 Root。**

---

## 1. 一句话定位

把 VSCode 的「编辑器 + 终端 + 插件 + 任务系统」搬到手机上，用一个 APK 完成
「写代码 → 补全 → 运行 → 调试 → 编译打包 → 部署」全流程。

## 2. 能力总览

| 能力 | 实现方式 | 说明 |
|------|----------|------|
| 代码编辑 | Sora Editor（原生） + Monaco（WebView，可选） | 语法高亮/自动补全/多标签 |
| 终端 | 内嵌 PTY + proot Ubuntu / Termux 兼容层 | 真实 shell，非模拟 |
| 语言运行 | 内置运行时 + proot 世界 | Python/Node/Go/Rust/C/C++/Java/Kotlin |
| 编译 | clang/gcc、javac/d8、cargo、go build、gradle | 调用工具链，输出 APK/so/二进制 |
| 依赖库 | pip / npm / cargo / go mod / maven 全量仓库 | 通过终端直接安装 |
| 插件 | DexClassLoader 动态加载 + JS 插件（QuickJS） | 双轨插件系统 |
| Root | libsu（Shizuku 回退） | 执行 `su -c`，挂载/改系统文件 |
| 调试 | DAP 协议（可选） + 日志面板 | 断点/单步（进阶） |
| 界面 | 单 Activity + 分栏布局 | 编辑器/文件树/终端可拖拽 |

## 3. 目录结构

```
PocketCodeStudio/
├── README.md              # 本文件：总览
├── docs/
│   ├── 01-架构设计.md      # 整体架构与数据流
│   ├── 02-技术选型.md      # 每个技术点的取舍与理由
│   ├── 03-功能模块.md      # 模块拆解 + 里程碑
│   ├── 04-UI设计.md        # 界面布局与交互规范
│   ├── 05-插件系统.md      # 插件规范与 API
│   └── 06-Root与终端.md    # Root/终端/权限模型
└── app/                   # （骨架）Android 工程
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/pocketcode/studio/…
        └── res/…
```

## 4. 快速阅读顺序

1. 先看 `docs/04-UI设计.md` 了解长什么样；
2. 再看 `docs/01-架构设计.md` 了解怎么搭；
3. `docs/03-功能模块.md` 看怎么做、按什么顺序做；
4. `docs/05-插件系统.md` / `docs/06-Root与终端.md` 看两个亮点能力。

## 5. 设计原则

- **精简界面**：默认只显示「编辑器 + 一行状态栏」，其余（文件树/终端/插件面板）按需唤出，避免信息过载。
- **真实可用**：终端是真 shell、编译是真工具链，不做花架子。
- **渐进增强**：不开 Root 也能写 Python/Node；开了 Root 才能做系统级操作。
- **离线优先**：核心编辑器与运行时内置，网络仅用于包管理与可选的云端补全。
