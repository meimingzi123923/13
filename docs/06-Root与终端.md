# 06 · Root 与终端

## 1. 为什么要 Root

编程软件「调用手机 Root 权限」主要用于：
- 读写/挂载系统分区（`/system`、`/vendor`）
- 修改 `/etc/hosts`、build.prop 等
- 在系统目录编译/部署 so、二进制
- 调用底层工具（iptables、mount、pm 等）
- 让内置终端拥有真正完整权限

## 2. 权限三态

```
① 无 Root（普通 App 沙箱）
    └─ 只能读写 /sdcard 与自身目录
② Shizuku（ADB shell 身份）
    └─ 可执行多数 shell 命令，无需 Root，需电脑/无线调试激活
③ Root（su）
    └─ 完整系统权限
```

UI 上在「设置 → Root」显示当前状态，并提供升级路径引导。

## 3. Root 实现（libsu）

```kotlin
// 检测
val hasRoot = Shell.getShell().isRoot

// 执行（同步/异步/流式）
Shell.cmd("id").exec()                       // 返回结果
Shell.cmd("mount -o rw,remount /system")     // 改写
    .submit { result -> /* 回调 */ }

// 常驻 Root 服务：只弹一次授权，后续复用
Shell.getShell()
RootService.bind(...)   // 减少重复 su 弹窗
```

**流式输出**接入终端面板：
```kotlin
val job = Shell.cmd("ping -c 4 8.8.8.8")
    .to(ArrayList()) { line -> terminalView.append(line) }
    .submit()
```

## 4. 终端实现（PTY）

核心：JNI 调用 `forkpty()` 创建伪终端，子进程跑 shell，App 读写 fd。

```kotlin
// 伪代码
val pty = nativeCreateSubprocess(fd, "/system/bin/sh")
// 输入 → 写入 master fd；输出 → 从 master fd 读取
```

要点：
- 用独立读线程阻塞读，避免卡 UI。
- 处理 `SIGWINCH`（终端尺寸变化）。
- 支持 UTF-8、ANSI 转义序列（颜色、光标）。
- 终端控件可用 `Termux 的 terminal-emulator` 或 Compose 自绘。

## 5. proot 与非 Root 完整 Linux

即使不 Root，也能用 **proot-distro** 跑完整 Ubuntu（用户态模拟 root）：

```bash
# 首次安装（App 内置命令）
proot-distro install ubuntu
# 进入
proot-distro login ubuntu
# 内部即可 apt install gcc python3 nodejs ...
```

App 里提供「运行时」页面：一键安装/删除 ubuntu、alpine 等发行版。
这样即使用户不 Root，也能获得**全面的依赖库**（apt 生态）。

## 6. 命令安全策略

| 命令类型 | 处理 |
|----------|------|
| 普通命令（`ls`, `python`） | 直接执行 |
| 写系统分区 | 二次确认弹窗，展示命令原文 |
| `rm -rf /` 类危险串 | 正则拦截 + 强提示 |
| 插件发起的命令 | 标注来源插件，用户确认 |
| Root 命令 | 额外 Red 色警示 + 显示 target |

## 7. Root 权限面板（设置页）

```
┌─────────────────────────────────┐
│ Root 状态：✅ 已授权 (Magisk)     │
│ 常驻服务：[ 开启 ]               │
│─────────────────────────────────│
│ 快捷操作：                        │
│  • 挂载 /system 为可写            │
│  • 编辑 /etc/hosts                │
│  • 查看 build.prop                │
│  • 重启系统服务                   │
│─────────────────────────────────│
│ 终端 Root 模式：[ 开 ]            │
└─────────────────────────────────┘
```

## 8. 合规与安全提醒

- Root 操作有**变砖/丢数据**风险，首次使用弹出免责声明。
- 所有 dangerously 操作记录到「命令历史」，可追溯。
- 不内置任何自动提权/破解行为，仅调用用户已授权的 `su`。
- 遵循系统安全：不在后台静默执行敏感命令。
