# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/) 与 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.0.5] — 2026-05-20

### Added
- **PPP 帧层** (`ppp/PppFrame.kt`)：HDLC-less 编解码（RFC 2637 §4.1），发送侧默认带 `FF 03` 前缀，接收侧兼容 ACFC（去前缀）与 PFC（单字节协议号）两种压缩形式
- **PPTP-GRE 封装层** (`pptp/GreFrame.kt`)：Enhanced GRE（K=1, Ver=1, 协议 0x880B），可选 S/A 字段；含 `stripIpv4()` 用于从 raw socket 收到的完整 IPv4 包里剥出 GRE 字节
- **LCP 协议** (`ppp/Lcp.kt`)：
  - `LcpCode`、`LcpOptionType`、`AuthProto` 常量
  - `LcpCodec`：包/选项的 ByteBuffer 编解码 + MRU/Magic-Number/Auth-Protocol 选项构造器
  - `AuthChoice` 枚举：PAP / MsChapV2 / MsChapV1 / Unknown
- **LCP 状态机** (`ppp/LcpStateMachine.kt`)：
  - 简化的 RFC 1661 §4.1 自动机：Initial → ReqSent → AckRcvd / AckSent → Opened
  - 主动 opener：发 ConfigureRequest（MRU=1400 + Magic-Number）
  - 协商策略：对端 MRU/Auth-Protocol/Magic/PFC/ACFC/ACCM 接受；MS-CHAP-V1 被 Nak 改为 MS-CHAP-V2；其余 Reject
  - 重传：3s 超时、最多 10 次，超出关闭
  - Echo-Request → Echo-Reply（含双向 Magic-Number 处理）
- **会话编排** (`pptp/PptpSession.kt`)：
  - 阶段机：Idle → ControlConnecting → CallSetup → BridgeStarting → LcpNegotiating → LcpOpen → Disconnecting → Closed/Failed
  - 自动解析服务器主机名为 IPv4（helper 需要目标地址做 sendto）
  - 把 helper bridge 的 RX 流 (`UdsBridge.received`) 接入 GRE→PPP→LCP 分发管线
  - 任一层失败统一 teardown
- **UI 第 ④ 区**：服务器/端口输入 + 「一键连接」/「断开」按钮 + 实时 phase / LCP state / Call-IDs / 错误显示；LCP Opened 时显示绿色验收提示

### Notes
- v0.0.5 终点：LCP 到 Opened。**没有**实际认证 → 大多数 PPTP 服务器会在认证阶段超时后断开。这是预期的。
- 服务器要求的认证类型（PAP 或 MS-CHAP-V2）已通过 `negotiatedAuth` 暴露，v0.0.6 会据此分支
- 已知简化：ConfigureNak 时不重新随机 Magic-Number；ConfigureReject 直接放弃；GRE seq/ack 仅做单向递增/最大值跟踪（未实现滑窗）

## [0.0.4] — 2026-05-20

### Added
- **PPTP 控制通道（TCP 1723）完整实现** — RFC 2637 §2 / §3
  - `ControlMessages.kt`：sealed hierarchy 覆盖 SCCRQ/SCCRP、StopCCRQ/StopCCRP、Echo-Request/Reply、OCRQ/OCRP、CDN、SLI、WEN（v0.0.4 客户端不接受 incoming call，故未含 ICRQ/ICRP/ICCN）
  - `ControlCodec.kt`：基于 ByteBuffer 的纯字节编解码，big-endian；ASCII 字段 NUL 填充；包含 Magic Cookie 0x1A2B3C4D 校验、消息长度 sanity check
  - `SessionState.kt`：每 call 独立的 16-bit Call-ID（SecureRandom 分配）+ GRE seq/ack 计数器（AtomicInteger，无锁）
  - `ControlChannel.kt`：协程化状态机 `Idle → Connecting → WaitSccrp → Established → WaitOcrp → CallUp → Stopping → Closed`
    - TCP 直连 + 8s 连接超时
    - 阻塞 read 循环：socket close → IOException → 优雅 teardown
    - RPC 模式（SCCRP/OCRP/StopCCRP）用 `CompletableDeferred` + `withTimeoutOrNull(8s)` 实现请求-应答
    - 自动 Echo-Request 心跳（默认 60s 间隔），暴露 success/failure 计数
    - 异步事件 `Channel<ControlMessage>` 投递 CDN/SLI/WEN 给 UI
    - 服务器主动发 StopCCRQ 时自动回 StopCCRP 并 teardown
- **UI 第 ③ 区**：服务器地址/端口输入、连接/呼叫/Ping/断开按钮、协商出的服务器 host/vendor、本端与服务器 Call-ID 显示、Echo 计数、服务器异步事件日志（滚动 10 条）

### Notes
- 控制通道与 helper 完全解耦：纯 TCP socket，不需 root，未来 VpnService 上线后用 `vpnService.protect(socket)` 防回环（v0.0.7 处理）
- v0.0.4 完成后，呼叫到达 `CallUp` 即得到 server 端的 Call-ID；下一步 v0.0.5 在此基础上启动 PPP LCP 协商
- 单元测试待补；目前依赖与真实服务器（accel-ppp / Windows RRAS / MikroTik）实测验证

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
