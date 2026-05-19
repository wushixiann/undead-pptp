# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/) 与 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.0.3] — 2026-05-19

### Added
- **Helper `bridge` 模式**：long-running 子进程，通过 AF_UNIX 与 app 做双向 IO
  - 协议：`4B peer_ip + 2B length + N B payload`（big-endian），TX/RX 同一帧格式
  - 用 `poll(2)` 多路复用 raw GRE socket 与 UDS
  - 支持抽象命名空间（`@name`）与文件系统路径两种 UDS 形式
  - app 关闭 UDS 时 helper 收到 EOF 自动退出
- **Kotlin `UdsFrame` 编解码**：纯字节流，无外部依赖
- **Kotlin `UdsBridge`**：包装 `LocalServerSocket`（抽象命名空间），用协程跑后台 RX/TX worker，把帧投递到 `Channel<UdsFrame>`
- **Kotlin `HelperLifecycle.startBridge()`**：经 `Shell.cmd().submit()` 异步拉起 helper，阻塞等待 helper 连入 UDS（默认 5s 超时）
- **UI bridge 测试区**：启动/停止按钮、目标 IP 输入、发送测试 GRE 包按钮、TX/RX 计数、RX 日志（滚动 20 条）

### Known Limits
- libsu 单 shell 限制：bridge 运行期间无法并发跑其他 `Shell.cmd()` 调用（足够 v0.0.3 但 v0.0.7+ 上 VpnService 时需要切到 RootService 或独立 shell）
- 测试 GRE 包是裸 12 字节结构，不含 PPP payload，不会被任何 PPTP 服务器认可 —— 仅用于验证 "字节进 helper → 字节出网卡" 的链路
- 用了抽象命名空间 UDS（`@pptp_bridge_<hex>`）。如果某些定制 ROM 的 SELinux 拒绝 magisk↔untrusted_app 抽象 socket 互通，会回退到文件系统路径（已在 helper C 侧支持，Kotlin 侧待加）

## [0.0.2] — 2026-05-19

### Fixed
- **修正 libsu 根权限检测时序错误**：`probe()` 之前用 `Shell.isAppGrantedRoot()` 做预检，但首次调用时 shell 尚未初始化、它返回 `null`，导致即便 Magisk 已授权也报"未获得 root 权限"。改为 `Shell.getShell()` 阻塞初始化（这一步才会触发 Magisk 弹授权框），随后用 `shell.isRoot` 做判定。

### Added
- 探测成功时回传诊断字符串：`id`、`getenforce`、`uname -r`，方便确认 SELinux 状态与内核版本
- 探测失败时输出多步排查清单（Magisk 安装、授权状态、`su` 路径）
- UI 主区域加 `verticalScroll` 以容纳更长的诊断文本

## [0.0.1] — 2026-05-19

### Added
- 项目骨架（Gradle Kotlin DSL，AGP 8.7.3，Kotlin 2.1.0，Compose）
- Native helper 源码 `app/src/main/cpp/helper_main.c`
  - `probe <iface>`：打开 raw GRE socket + SO_BINDTODEVICE，输出 `OK <iface>` 后退出
  - `listen <iface>`：probe 后进入接收循环，stderr 打印来源 IP + 头 32 字节
  - 编译为 `libpptp_helper.so`（PIE 可执行文件，通过 jniLibs 机制提取到 `nativeLibraryDir`）
- Kotlin 侧 `HelperLifecycle.probe()` 使用 libsu 经 `su -c` 调用 helper
- Compose UI：版本/里程碑信息 + "检测 root + 原始 GRE socket" 按钮
- 接口探测工具 `NetworkUtil.activeUnderlayInterface()`（ConnectivityManager API）

### Notes
- v0.0.1 唯一目标：在已 root 的目标设备上验证 `socket(AF_INET, SOCK_RAW, IPPROTO_GRE)` 在 magisk SELinux 域下可成功打开。这是后续所有里程碑的前提。
