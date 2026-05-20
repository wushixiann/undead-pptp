# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/) 与 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.0.7] — 2026-05-20

### Added
- **IPCP 状态机** (`ppp/IpcpStateMachine.kt`) — RFC 1332
  - 与 LCP 同形的 7 状态自动机，复用 LcpPacket/LcpCodec 编解码（IPCP 包结构与 LCP 完全相同，只是选项语义不同）
  - 选项：IP-Address (3), Primary-DNS (129), Secondary-DNS (131)
  - 我方初始 ConfReq 全填 0.0.0.0 → 服务器 Nak 时采纳建议值再发 → 服务器 Ack → Opened
  - 对端 ConfReq 直接接受（服务器侧 IP）
- **VpnService 子类** (`vpn/PptpVpnService.kt`)
  - `BIND_VPN_SERVICE` 系统服务，前台 specialUse 类型
  - 接收 `ACTION_START` Intent 携带 host/port/user/password
  - 内部持有 PptpSession + TunPipe
  - IPCP Opened 回调里用 `VpnService.Builder` 建立 TUN：
    - `setMtu(1400)` `addAddress(localIp/32)` `addRoute(0.0.0.0/0)`
    - addDnsServer × 主备 DNS
    - `setConfigureIntent` 跳回 MainActivity
  - 暴露 `State` StateFlow 给 UI 观察（phase / localIp / peerIp / lastError）
  - `onRevoke()`：系统侧（设置 → VPN → 断开）触发优雅退出
- **前台通知** (`vpn/ForegroundNotification.kt`)
  - Android 8+ NotificationChannel `pptp_vpn`，importance LOW
  - 通知点击跳回 MainActivity
  - Title/body 随 phase 变化（连接中 / 已连接 / IP=…）
- **TunPipe** (`vpn/TunPipe.kt`)
  - 后台协程读 TUN fd（阻塞 FileInputStream）→ 调 `session.sendIpv4(packet)`
  - `deliver(packet)` 把收到的 PPP IPv4 写回 TUN fd
  - 只处理 IPv4（首字节高 4 位 = 0x4），跳过 IPv6（未来 IPCPv6 处理）
- **PptpSession 扩展**：
  - 构造函数加 `socketProtector` 和 `onIpcpOpened` 回调
  - 阶段新增 Authenticated → IpcpNegotiating → IpcpOpen → Connected
  - 认证成功后自动 startIpcp()
  - 新 `bindTun()` 把 TUN 投递回调注入；从此 PPP IPv4 帧被转给 TUN
  - 新 `sendIpv4()` 给 TunPipe 调用，对应方向 TUN → wire
- **ControlChannel**：socketProtector 在 connect 之前调用，避免 TCP 握手包穿入 VPN
- **AndroidManifest**：注册 PptpVpnService，附 `BIND_VPN_SERVICE` 权限、specialUse 类型、`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明
- UI 第 ④ 区：用 VpnService.prepare() 获取系统授权后再 startForegroundService；连接/断开都通过 Intent；状态来自 `PptpVpnService.observable` 而非直接绑定 PptpSession

### Known Limits (v0.0.7)
- **未启用 MPPE/CCP**：accel-ppp / Windows RRAS 默认强制 MPPE，会在 IPCP 后立刻 TermReq。测试 v0.0.7 时必须把服务器配置改成 `mppe=no required` 或 `--require-mppe=no`。v0.0.8 加 MPPE 后此限制解除
- **底层网络切换不重连**：WiFi↔4G 切换会断开（v0.1.0 处理）
- **POST_NOTIFICATIONS 用户拒绝时通知不显示**，但服务仍可运行（API 33+ 行为）

## [0.0.6] — 2026-05-20

### Added
- **Crypto.kt**：自实现 MD4 (RFC 1320, 纯 Kotlin, ~150 行) + SHA-1 包装 + DES 单块加密（7→8 字节奇偶位扩展）+ UTF-16-LE 编码工具
  - MD4 自实现的原因：2026 年 Android 默认安全提供方已不可靠提供 MD4
  - DES 仍走 `javax.crypto.Cipher("DES/ECB/NoPadding")` —— 只用作 MS-CHAP-V2 的 ChallengeResponse 算法，不做加密存储
- **PAP 认证** (`ppp/PapAuth.kt`) — RFC 1334
  - 单次 Authenticate-Request → Ack/Nak 流程
  - 明文密码，仅在用户显式接受风险时使用
- **MS-CHAP-V2 认证** (`ppp/MsChapV2Auth.kt`) — RFC 2759
  - 完整 Challenge(1) → Response(2) → Success(3) / Failure(4) → Success-ack
  - `ntPasswordHash() = MD4(UTF-16-LE(password))`
  - `challengeHash() = SHA1(peerChal ‖ authChal ‖ user)[0:8]`
  - `challengeResponse()`：3 次 DES 用 PasswordHash 填充为 21 字节的三段密钥
  - `generateAuthenticatorResponse()`：用 RFC 2759 §8 的 Magic-1/Magic-2 常量做服务器签名验证（不匹配时记日志但仍接受，提高互通）
  - `deriveMppeKeys()`：导出 MPPE master key 材料（v0.0.8 启用 MPPE 时使用）
  - 自动 strip `DOMAIN\\user` 中的 domain（CHAP 哈希要的是裸用户名）
- **PptpSession 扩展**：
  - 新增 phase: `Authenticating`, `Authenticated`
  - LCP Opened 后根据 `lcp.negotiatedAuth` 自动分支启动 PAP 或 MS-CHAP-V2
  - 暴露 `authMessage` StateFlow 给 UI（服务器返回的消息）
  - 暴露 `mppeKeys` 字段（成功 MS-CHAPv2 后填充）
- UI: 新增用户名/密码输入框；显示认证消息、MPPE 主密钥派生标志、Authenticated 绿色验收提示

### Notes
- v0.0.6 终点：认证成功，链路处于 Authenticated 状态。**仍然没有 IP 流量**：IPCP（v0.0.7）才会协商 IP 地址、CCP/MPPE（v0.0.8）开启加密、VpnService 创建 TUN 把流量接进来
- MS-CHAPv2 Authenticator Response 验证：不匹配时记 warning 但仍接受成功，因为部分服务器（特别是 RouterOS 早期版本）的 S= 字段格式略有偏差
- MS-CHAP-V1 与未知认证协议会直接判为不支持并断开 —— LCP 协商时已把 MS-CHAP-V1 Nak 改为 MS-CHAP-V2

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
