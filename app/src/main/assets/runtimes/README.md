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

## 已内置：proot + Alpine 根文件系统（推荐）

本目录已内置一套**开箱即用**的 Linux 环境，使 gcc / python3 / node 等直接可用：

```
bin/proot                          ← 静态 aarch64 proot（无任何依赖）
rootfs/alpine-minirootfs-aarch64.tar.gz  ← Alpine 3.20 aarch64 迷你根文件系统
```

运行时会在首次执行非 JS 程序时把 rootfs 解压到 `filesDir/sandbox/rootfs/`，
然后用 proot 把用户命令**包进 rootfs 内执行**：

```
<sandbox>/bin/proot --link2symlink -0 -r <sandbox>/rootfs \
  -b /dev -b /proc -b /sys -b /sdcard -b /storage -b /mnt \
  -w /root /usr/bin/env -i HOME=/root PATH=… /bin/sh -lc "<用户命令>"
```

- 工程目录通过 `-b /sdcard` 绑定进 rootfs，命令里的 `cd /sdcard/…` 依然有效；
- 若某语言工具缺失（如 `gcc`），会自动执行 `apk add --no-cache build-base`
  现场安装（**首次需要联网**），装到 rootfs 内持久保存。

| 语言       | 自动安装的 Alpine 包   |
|------------|------------------------|
| Python     | `python3`              |
| JavaScript | `nodejs`               |
| TypeScript | `nodejs npm`           |
| C / C++    | `build-base`（gcc/g++/make） |
| Go         | `go`                   |
| Rust       | `rust cargo`           |
| Java       | `openjdk17`            |

> 注意：为了能执行沙盒私有目录中的可执行文件，`targetSdk` 设为 28
> （Android 10+ 对 targetSdk≥29 的应用启用 W^X 限制；Termux 同理）。

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