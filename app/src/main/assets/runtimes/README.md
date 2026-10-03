# 内置运行时（assets/runtimes）

把你希望 App 自带的语言运行时放在这里，App 启动时会**自动释放到应用私有沙盒**：

```
filesDir/sandbox/bin/    ← 由 assets/runtimes/bin/  复制而来（自动 chmod +x）
filesDir/sandbox/lib/    ← 由 assets/runtimes/lib/  复制而来
filesDir/sandbox/tmp/    ← 临时目录（TMPDIR 指到这里）
```

执行程序时，BuildRunService 会注入：

- `PATH=<sandbox>/bin:$PATH`
- `LD_LIBRARY_PATH=<sandbox>/lib:$LD_LIBRARY_PATH`
- `TMPDIR=<sandbox>/tmp`

因此 `python3 main.py` / `node main.js` 等命令会**优先命中沙盒里的运行时**，
完全不依赖手机 `/system/bin/sh` 中是否装有这些解释器。

## 放置约定

| 语言       | 需要的文件                        |
|------------|-----------------------------------|
| Python     | `bin/python3`，依赖库放 `lib/`    |
| JavaScript | `bin/node`（或使用内置 QuickJS，见下） |
| C / C++    | `bin/gcc`、`bin/g++`（含其 cc1/as/ld） |
| Go         | `bin/go`                          |

> 运行时必须是 **Android 可执行的 ELF**（arm64-v8a / armeabi-v7a），
> 或带有对应解释器（例如 `bin/python3` + `lib/python3.x/`）。

## JavaScript 无需放置

`.js` 默认由 App **进程内的 QuickJS 沙盒**执行（见 `core/sandbox/JsSandbox.kt`），
不需要 `bin/node`。只有当你放了 `bin/node` 时才会改用 node。

## 其它导入方式

- **运行时安装包（zip）**：`SandboxRuntime.installFromZip(zip)`，
  zip 内 `bin/**` 与 `lib/**` 会被原样解出（`bin/` 自动 chmod +x）。
- **单文件导入**：`SandboxRuntime.importExecutable(file, "python3")`。

详见 `docs/10-应用沙盒运行时.md`。
