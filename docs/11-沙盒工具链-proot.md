# 11 · 沙盒工具链：内置 proot + Alpine

## 背景

第 12 轮产出的 APK 能装能开，但**编写的程序跑不起来**，报「找不到 python3 / gcc」之类。
根因：执行链路把命令交给系统 `/system/bin/sh` 的 PTY，而 Android 自带的 shell 里
根本没有 `python3`、`node`、`gcc` 这些解释器/编译器（它们是 Linux 发行版才有的）。

第 13 轮的 `SandboxRuntime` 把「运行时目录」搬进了应用私有沙盒（`filesDir/sandbox`），
但运行时二进制还没真正放进去 —— 这就是本次要补齐的缺口。

## 方案：内置 proot + Alpine aarch64 rootfs

Android 上的 app 不能擅自安装系统软件，但可以**自带一套最小 Linux 发行版**，
用 `proot`（用户态 chroot，不需要 root）把程序放进去执行。

工程内置（`app/src/main/assets/runtimes/`）：

| 文件 | 说明 | 来源 |
|------|------|------|
| `bin/proot` | 静态 aarch64 proot，1.5 MB，无任何动态依赖 | `ysdragon/proot-static` v5.4.0 |
| `rootfs/alpine-minirootfs-aarch64.tar.gz` | Alpine 3.20 迷你根文件系统，3.9 MB | `dl-cdn.alpinelinux.org` |

运行时流程：

```
BuildRunService.run()
  └─ SandboxRuntime.ensureExtracted()      释放 bin/proot
  └─ SandboxRuntime.ensureRootfs()          解压 rootfs（幂等，带 .ready 标记）
  └─ 若语言工具缺失 → apk add --no-cache <pkg>   在 rootfs 内现场安装
  └─ SandboxRuntime.prootWrap(userCmd)     把命令包进 proot
       └─ 写入 TerminalService 的 PTY 执行
```

### 执行命令形态

```sh
<sandbox>/bin/proot --link2symlink -0 -r <sandbox>/rootfs \
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random \
  -b /sdcard -b /storage -b /mnt \
  -w /root /usr/bin/env -i HOME=/root LANG=C.UTF-8 TMPDIR=/tmp \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /bin/sh -lc "<cd /sdcard/… && gcc main.c -o a.out && ./a.out>"
```

要点：

- `-0`：伪造 root 身份（Alpine 里 apk 需要）；
- `-b /sdcard`：把工程目录绑定进 rootfs，命令里的 `cd /sdcard/…` 依然有效；
- `--link2symlink`：Android 文件系统上兼容 tar 里的硬链接（busybox 小程序）；
- 不再注入沙盒 `PATH/TMPDIR`（rootfs 内没有这些路径），故 `buildCommand(..., sandboxEnv = false)`。

## 关键工程决策

### 1. `targetSdk = 28`

Android 10（API 29）起对 **targetSdk ≥ 29** 的应用启用 W^X：
**禁止 execve 应用私有目录中的可执行文件**。
而 proot 与整个 rootfs 都在 `filesDir/sandbox/` 里，必须执行 —— 因此把 `targetSdk`
降到 28（`minSdk` 仍为 26，`compileSdk` 仍为 37）。这正是 Termux 的做法。
本应用为侧载开发工具，不涉及 Play 商店的 targetSdk 门槛。

### 2. tar.gz 解包用 commons-compress

新依赖 `org.apache.commons:commons-compress:1.27.1`，用于处理 Alpine rootfs 里的
符号链接 / 硬链接 / 目录（这些用 `java.util.zip` 无法正确还原）。
解包时按 owner-execute 位补 `setExecutable(true)`。

### 3. 自动安装工具链

首次运行某语言时，若 rootfs 内没有对应工具，自动在 proot 内执行
`apk add --no-cache <包>`（见 README 的映射表）。首次需要联网，装好后持久保存在 rootfs。

## 语言 → Alpine 包

| 语言 | 包 |
|------|----|
| python | `python3` |
| javascript | `nodejs` |
| typescript | `nodejs npm` |
| c / cpp | `build-base` |
| go | `go` |
| rust | `rust cargo` |
| java | `openjdk17` |

JavaScript 仍优先走上轮的**进程内 QuickJS**（无需 rootfs），只有存在 `bin/node` 时才用 node。

## 后续可扩展

- 预装工具链的 rootfs 镜像（避免首次联网）；
- 从网络下载运行时包（`installFromZip` 已就绪）；
- 支持 armeabi-v7a（需要对应的 proot/rootfs）。
