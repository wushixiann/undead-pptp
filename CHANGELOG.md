# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/) 与 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.2.4] — 2026-05-22

### Reverted — v0.2.3 的 256-boundary rekey 是错的方向

v0.2.3 装上后 6 秒钟服务器就发了 15 个 LCP Protocol-Reject (code=8) 然后断了。Protocol-Reject 意味着：**server 解了我们的 MPPE 包之后看到的 inner protocol 它不认识**。

复盘：v0.2.3 我加的"每 256 包额外旋转一次"理论是错的。RFC 3078 §6.3 写得歧义，但 pppd 在 stateless 模式实际**不做**这一步。我加上去之后包 0..255 双方对齐，到了包 256 我多旋了一次，从此发送密钥跟 server 的接收密钥差一个 SHA1+RC4 旋转 → server 解出垃圾 inner protocol → Protocol-Reject 风暴。

撤销 encrypt 和 advanceRecvKeyTo 的 256-boundary 额外 rekey。回到 v0.2.2 的"每包一次"策略。

### Kept from v0.2.3
- UDS Channel buffer 64 → 1024：覆盖 burst 不丢包
- `trySend → send (suspending)`：consumer 慢时 backpressure 传到 kernel 而不是我们静默丢

### Added — 诊断
- `advanceRecvKeyTo` 在 `forwardDelta > 1024` 时打 warning。如果以后又出问题，能看到是不是大量丢包（drops > 4096 是潜在的不可恢复状态）

### 实话
v0.2.2 那次"用 10 秒就坏"我归因于 buffer overflow 应该是对的。256-boundary rekey 完全是我加戏。这次实测确认了。

## [0.2.3] — 2026-05-22

### Fixed — 后台 ~10 秒后解密全乱码

实测 v0.2.2 装上能用，UI 进入后台 ~5 秒后所有 MPPE 解密变垃圾。诊断出两个独立的 bug 都在起作用：

**Bug A — UDS Channel 容量 64 + trySend 静默丢包**：
`UdsBridge.received` 的 `Channel(capacity = 64)` 太小，应用进后台 CPU 被 throttle 时 consumer 慢，channel 满了 `trySend` 静默丢包。每丢一个 MPPE 状态就跟服务器差一次旋转，累积丢 > 4096 包后 12-bit CC 取模 wrap，密钥永远对不上。

修复：`capacity = 1024`（覆盖一整个 CC wrap 窗口）+ `trySend → send` （suspending，channel 满了 IO 循环阻塞读 UDS，让 backpressure 传到 kernel raw socket，丢包发生在 kernel 而不是我们）。

**Bug B — 漏了 MPPE 256-boundary 的额外 rekey（pppd 兼容性）**：
RFC 3078 §6.3 写："To maintain backwards compatibility, the encryption tables are changed every 256 packets. In stateless mode, this happens once for every packet sent." 我读成"stateless = 每包一次，OK"。但 pppd / Windows RRAS 的实际实现是 **"每包一次 + 每 256 包额外一次"**。

10 秒 × ~25 包/秒 ≈ 250 包，跟"用了 10 秒就崩"完美吻合：包 0..255 用 N+1 次旋转密钥，包 256 服务器多旋一次 (257 次) 我们没旋，从此差一个旋转 → 所有后续包解密垃圾。

修复：`encrypt` 和 `advanceRecvKeyTo` 都加上 256-boundary 检查 (`cc != 0 && cc and 0xFF == 0`)，遇到 256/512/768... 时多做一次 `nextKey`。advanceRecvKeyTo 改成 step-by-step 推进而不是直接 forward by delta，确保在 256 边界一定额外 rekey。

### Notes
两个 bug 都修了，浏览应该能稳定。如果还崩，请贴新日志，重点看 `MPPE inner protocol 0xXXXX ignored` 是否还出现以及在哪些包数附近。

## [0.2.2] — 2026-05-21

### Fixed (critical) — MPPE 长会话掉链

实测发现 v0.2.1 浏览刚开始好用，过几分钟就开始大量解密失败。诊断日志显示同一个 Mppe 实例内，cc=0..7 解密**两次**都成功（不同密钥），但两次中间没有 init 日志。其实那是服务器的 CC 在 12 位空间里跑了一圈 wrap 回 0，期间几千个 IPv4 包被我们正确解密静默投递给 TUN（不打日志），所以日志里看不到中间过程。

bug 在 `advanceRecvKeyTo`：原代码在 `forwardDelta > 0x800 (2048)` 时认为"是服务器重置了 CC，restart from base"。但 stateless MPPE 模式下**服务器不会主动重置 CC**（只有我们发 Reset-Request 才会，并且那时也不该重置我们的 recv 状态）。所谓"backward 跳"99% 是 **我们 buffer 溢出丢了一段包**导致 lastRecvCc 落后于服务器真实 CC。重启 base 后用了完全错误的密钥，所有后续包解密失败。

修复：删除 backward branch，**永远 forward rotate by (delta mod 4096)**。最坏情况一次包旋转 ~4000 次 nextKey（几毫秒，可接受），但永远跟服务器对齐。

### Improved
- **`PptpSession.teardownNetwork` 加 `scope.cancel()`**：原来 disconnect/fail 后 SupervisorJob 不取消，重复连接断开会累积后台协程（状态观察器、helper exit 等）
- **Mppe 调试日志全部降到 DEBUG**：init/encrypt/decrypt 的 key 指纹只在 `Log.isLoggable(TAG, DEBUG)` 时打印，生产环境不再刷屏
- UI 里程碑标签更新为 v0.2.2

### Acknowledged Limitations (not fixed yet)
- 网络底层切换 (WiFi↔蜂窝) 自动重连
- Kill-switch（VPN 异常时阻止明文出口）
- 多服务器配置管理
- 应用分流 (per-app routing)
- 助手二进制每 5s 周期统计日志略嘈杂（生产可改为按需触发）
- 调试用 UI 段 ① ② ③（probe / bridge / control 独立测试）后续可隐藏到「开发者模式」开关

## [0.2.1] — 2026-05-21

### Fixed — CCP 接受了 MPPC 压缩位（C bit）

v0.2.0 装上后 ping/IP 偶尔通，浏览器持续失败；解密后的 inner protocol 30%+ 是 `0x00XX`（低字节奇数）的奇怪值，30%+ 是高位随机字节。这不是 RC4 keystream 不对齐（那会是均匀随机），而是有结构。

原因：CCP 协商时收到服务器的 MPPE 选项 (类型 18)，我只检查了 H (128-bit) + S (stateless) 是否都置位，**就把服务器原始 4 字节 ACK 回去**。如果服务器同时设了 C bit (0x00000001, MPPC compression)，等于跟我说"我要压缩+加密都开"，然后服务器先 MPPC 压缩、再 MPPE 加密。我只解了加密、没解压缩 → 看到的就是 MPPC 流头部那些 `0x00XX` 字段。

修复：CCP ACK 严格只接受 `H + S` 完全匹配的位集；任何额外的位（C / D / L / M / Stateful）都 NAK 回去带上我们能接受的组合，强制服务器降级到纯加密。

### Diagnostics
- 解密日志改成前 8 字节 hex dump 而不是协议字段。下次能看到 `00214500...`（正确 IPv4）还是 `00xx...`（MPPC 头）就一目了然。

### Notes 给用户
新版装上重新连，logcat 里第一批 MPPE decrypt 应该长这样:
- 期待: `MPPE decrypt cc=0 ... first8=00214500...` (0x00 0x21 = IPv4 协议; 0x45 = IPv4 头第一字节)
- 还错: `00cb88...` 之类 → 把 logcat 完整贴过来再看

## [0.2.0] — 2026-05-21

### Fixed — MPPE 内层 PPP 协议字段 PFC 解析

用户确认 Windows PPTP 在同一网络同一服务器能正常浏览网页，所以服务器 MSS clamping、forwarding 都没问题，bug 在客户端。

排查到 `handleMppeRx` 总是按 2 字节读 inner PPP 协议字段，但 LCP 协商时我们 ACK 了服务器的 **Protocol-Field-Compression (PFC)** 选项。如果服务器对 inner PPP 帧也启用 PFC 压缩，IPv4 协议字段会变成单字节 `0x21` 而不是双字节 `0x00 0x21`。原代码读两字节得到 `0x21XX`（XX 是 IPv4 首字节 0x45 的影子），整个 IPv4 包向后偏移一字节，kernel 看到 IPv4 头格式不对就静默丢包。

ICMP echo reply 因为对包结构相对宽容，**有时**能侥幸过；TCP 对头部完整性极严格，PFC 偏移 1 字节就 100% 失败。这解释了 "ping 通但 TCP 不通"。

修复：用 PppFrame.decode 同样的 PFC 检测（首字节低位为 1 → 单字节协议字段）。

### Improved diagnostics
- "MPPE inner protocol 0x????  ignored" 日志加上字节数，方便看包大小分布

## [0.1.9] — 2026-05-21

### Fixed
- **TUN MTU 1400 → 1380** 缓解 TCP 大包黑洞

实测发现 `ping baidu.com` 通（DNS 解析 + ICMP 来回都正常），但浏览器打不开 baidu.com。典型的 **PPTP MSS / PMTUD 黑洞**：

- ICMP echo 包很小（64-100 字节），不触发 MTU 限制
- HTTPS 握手 TLS ClientHello 通常 1-2 KB，大包到了某个不允许分片又比 PPTP 隧道窄的链路上被默默丢了
- 上行小包能过、下行大包过不来 → TCP 卡死

MTU 1400 在标准 1500 以太网下还能撑住，但 PPTP 服务器与目标网站之间若有 1492 PPPoE 链路（中国大量光猫拨号是 PPPoE）就掉进黑洞。降到 1380 给链路上更多缓冲。

### Notes — 配套服务器端 fix（更治本）

服务器跑：
```bash
sudo iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
sudo netfilter-persistent save
```

这条 MSS clamping 规则会改写 SYN 包里的 MSS 字段，让两端 TCP 自动协商安全的小包。**几乎所有"PPTP 能 ping 不能浏览网页"的故障靠这条解决**。

## [0.1.8] — 2026-05-21

**MPPE 加解密 v0.1.7 已实证通过**，IP 流量打通。本版本主要是 DNS 路由细节打磨：

### Improved
- `VpnService.Builder.setMetered(false)`：把 VPN 标成非计量网络。某些应用 (微信、Chrome 等) 在"避免移动数据"时会因 metered 而拒绝走 VPN，这一行让它们正常走
- 把协商出的 DNS 服务器**显式 addRoute(dns, /32)**：理论上 0.0.0.0/0 catch-all 已经覆盖，但实测某些 Android 路由缓存的边角情况下，显式 /32 路由更稳

### Notes — IP 通了但域名不通的常见原因

不是客户端 bug，是 **PPTP 服务器侧缺 NAT/转发规则**。SSH 上服务器跑：

```bash
sudo sysctl -w net.ipv4.ip_forward=1
sudo iptables -t nat -A POSTROUTING -s 192.168.1.0/24 -o eth0 -j MASQUERADE
sudo iptables -A FORWARD -s 192.168.1.0/24 -j ACCEPT
sudo iptables -A FORWARD -d 192.168.1.0/24 -j ACCEPT
```

接口名 (eth0) 按 `ip route get 8.8.8.8` 实际改；客户端 IP 段按 IPCP 给的实际改。

若服务器配齐了仍不通，看手机：设置 → 网络与互联网 → **私人 DNS = 关闭**。Android 9+ 的 Private DNS 走 DoT 直连，会绕过 VPN 提供的 DNS。

## [0.1.7] — 2026-05-21

### Fixed — MPPE 漏了 pppd 的 "initial rekey" 步骤

v0.1.6 把 `nextKey` 里 RC4 self-encrypt 整个去掉，结果还是垃圾。
根因不是 RC4 该不该做，而是 **客户端漏掉了一次初始 SHA1 旋转**。

pppd 的实际流程（多个版本 mppe.c 源码确认）：

1. `session_key = asymmetric_start_key` (SHA1 only，无 RC4)
2. `mppe_init` 调用 `mppe_rekey(initial=1)`：做一次 SHA1（**不含 RC4**），结果记为 base
3. 每发/收一个包前调用 `mppe_rekey(initial=0)`：再做一次 SHA1+RC4，得到该包用的密钥

所以 cc=N 的包用的密钥 = asym 上做 **1 次 SHA1** + **N+1 次 SHA1+RC4** 旋转。

我之前从 asym 开始直接每包旋转一次，**漏了 step 2 的那次"初始 SHA1"**。整个序列都比 pppd 少一次 SHA1 变换，所以两边密钥永远对不上。

修复：
- 新增 `sendBaseKey` / `recvBaseKey` = 在 asym 上做一次 SHA1-only 旋转（`sha1Only` 函数）
- `sendCurrent` / `recvCurrent` 现在初始化为 base，不再是 init
- `advanceRecvKeyTo` 首包从 base reset 然后旋转 cc+1 次（每次都是 SHA1+RC4）
- `nextKey` 恢复 SHA1 + RC4 self-encrypt（v0.1.5 时是对的，v0.1.6 误删了）
- `reset()` 把 sendCurrent 复位到 base（不是 init）

### Diagnostics
- 启动时 logcat 多打一行 base key 指纹 + cc=0 的会话密钥指纹（`MPPE first session keys (cc=0)`）。如果还是解不开，从这一行能判断算法是否再次走偏。

## [0.1.6] — 2026-05-21

### Fixed
- **`nextKey` 去掉多余的 RC4 self-encrypt**：之前在 SHA1 输出之后又做了一遍 `RC4(sha, sha)`。重读 RFC 3078 §7.7 / §7.8：RC4 self-encrypt 只在 §7.8 「Reducing the SessionKey Size」里出现，**仅对 40/56-bit 弱化密钥使用**；128-bit 模式直接用 SHA1 截断后的结果。我们多做了这一步，等同于客户端做了一次 RC4 变换、服务器没做，密钥永远差一个 RC4 变换的距离 —— 双向解密都是垃圾。

### Added (Diagnostics)
- MPPE 初始化打印 master key / send-init / recv-init 的前 4 字节指纹到 logcat (`Mppe:I` tag)
- encrypt / decrypt 前 4 个 cc 的密钥与首 4 字节明文都会打到 logcat，方便比对算法对齐

如果这版仍解不开，请贴 `adb logcat -s Mppe:V PptpSession:V Lcp:V Ccp:V` 输出 —— 我们能从首轮密钥指纹判断算法是否真的对齐。

## [0.1.5] — 2026-05-21

### Fixed (critical) — MPPE 加解密双向失效

实战发现 LCP/Auth/IPCP 全部成功，TUN 也建立了，但 MPPE 解密产物 inner protocol 永远是随机值（0x98c1, 0xa422, ...），同时服务器一直发 CCP Reset-Request。原因是两个独立的 bug 凑齐了：

**Bug 1：encrypt 写错 flag byte**
- 写的是 `0xA0` (A=1, **C=1**, D=0)
- 正确应该是 `0x90` (A=1, **D=1**)
- 我把 D bit 位置和 C bit 位置弄反了：RFC 3078 §2 layout 是 `ABCD` 占 byte 0 的高 4 位，D 是 bit 4 = 0x10
- 后果：服务器收到我们的包看到 D=0 → 不解密；C=1 → 当成 MPPC 压缩数据；自然解析失败 → 一路发 Reset-Request

**Bug 2：decrypt 用错密钥位置**
- stateless MPPE 的关键约定：cc=N 的包用 init 旋转 N+1 次之后的密钥；服务器和客户端各自从自己的 cc 计数派生密钥
- 原 decrypt：不管首包 cc 是几，都只旋转 1 次密钥
- 结果：如果第一个收到的包 cc≠0（这在服务器 reset 后 cc 飘走、或我们错过早期包时一定会发生），永远拿不到正确密钥 → 解密产物是垃圾

  改成：维护 `lastRecvCc`，按收到的 cc 直接派生密钥（首包从 init 旋转 cc+1 次，后续按增量旋转）。彻底脱离"必须从 0 开始按序到达"的脆弱前提。

**Bug 3 (附带)：reset() 把 recv 状态也清了**
- 收到 CCP Reset-Request 时之前的 reset() 把 sendCC / recvCurrent / lastRecvCc 全部清零
- 但服务器收到 Reset-Request 后不会重启自己的 send 计数 —— 它只 reset 它的 recv 状态
- 所以我们 recv 状态本就不该跟着重置；现在 reset() 只复位 send，recv 用 advanceRecvKeyTo() 自动跟服务器的 cc 对齐

### 期望效果

修复后流程应该走到 Connected 阶段并保持稳定；UI 中 GRE RX 计数应当随访问外网持续增长；不再看到 "MPPE inner protocol 0x????" 日志（除非协议确实非 0x0021）。如果还是断，请贴新日志（特别是 LCP TerminateRequest 之前的 30 秒）。

## [0.1.4] — 2026-05-21

### Fixed (critical)

**底层接口选错了** —— v0.1.3 用户日志显示 "底层接口: rmnet1" 但用户在 WiFi 上。原因是 `NetworkUtil.activeUnderlayInterface` 迭代 `cm.allNetworks` 时顺序不定，碰巧先看到蜂窝就拿了。helper `SO_BINDTODEVICE` 把 raw socket 绑死在 rmnet1，于是 GRE 真的就走蜂窝了（这才被运营商/CGNAT 吃掉），与 Windows 走默认路由（自然走 WiFi）行为不一致。

修复策略：
1. **首选 `cm.activeNetwork`** —— Android 系统已经按用户/连通性选好的默认网络。WiFi 已验证可用时，默认就是 WiFi
2. **回退：按优先级 WiFi → Ethernet → Cellular 遍历**
3. **跳过 VPN 网络**（避免在已有其他 VPN 的设备上递归套娃）

### Added (Diagnostics)
- `NetworkUtil.listCandidates(context)`：列出所有非 VPN 网络的接口名、传输类型、是否 validated
- PptpSession 启动时 `Log.i` 把"选了哪个接口 + 所有候选"打进 logcat，便于排查 Android 没看到 WiFi 的情况

## [0.1.3] — 2026-05-21

### Added (Diagnostics)

第一次实战发现 LCP ConfReq 在蜂窝网 (rmnet1) 上无响应。可能原因是
运营商封锁 IP 协议 47 (GRE) 或 CGNAT 无法回程，需要更多观测数据。
加一组诊断面板帮助现场定位：

- **UdsBridge 加 `txCount` / `rxCount` StateFlow**：累计统计 app↔helper
  之间过帧数。RX 一直为 0 意味着 helper 收不到任何 GRE 回包
- **PptpVpnService.State 暴露 iface / greTx / greRx**，每秒刷新一次
- **UI 第 ④ 区显示**：
  - 底层接口名（rmnet*/ccmni* 自动标红警告"蜂窝网常封 GRE"）
  - GRE TX/RX 计数；TX>3 且 RX=0 时标红"发出去没回包"
- **Helper 接 liblog**：bridge 启动、sendto 错误、每 5s 一次 TX/RX
  统计走 logcat
  - `adb logcat -s pptp_helper:V` 即可看实时统计与错误
  - sendto 失败时打印 errno + peer IP + len
  - 退出时打印最终统计

### Diagnosis Guide

- TX=0, RX=0 → app→helper UDS 桥不通（应当不会，bridge 已 Connected）
- TX>0, RX=0 → helper 在发 GRE，但没收到回包：
  - 运营商封 IP 协议 47（蜂窝网常见）
  - CGNAT 吃了回程（GRE 无端口，NAT 无法跟踪反向流）
  - 服务器没收到，或收到了但没回（需要服务器侧 `tcpdump -i any proto gre` 验证）
- TX>0, RX>0 但 LCP 还卡 ReqSent → 收到的包 Call-ID 不匹配
  （bug 在 GreFrame/PptpSession 里 — 这种情况贴出 logcat 让我看）

## [0.1.2] — 2026-05-21

### Fixed
- **16 KB page-size 兼容**：CMakeLists.txt 加 `-Wl,-z,max-page-size=16384` 与 `-Wl,-z,common-page-size=16384` 链接器标志。Android 15+ 部分设备运行在 16 KB 内存页下，不带这个对齐的 .so 在它们上加载会失败。NDK r26c+ 在 API 35+ 默认开启此对齐，但显式声明确保跨 NDK 版本稳定，并消除 `app-debug.apk is not compatible with 16 KB devices` 打包警告。

## [0.1.1] — 2026-05-21

### Fixed
- **编译错误** (`helper/UdsBridge.kt` line 134)：`ChannelResult.onFailure` 在我们项目传递依赖到的 kotlinx-coroutines 版本里没有这个 API（它在较新 1.7+ 才补全 ChannelResult 的 onSuccess/onFailure/onClosed；项目未显式声明 coroutines 依赖，靠 androidx-lifecycle/activity-compose 传递的版本不够新）。改成 `isFailure` + `exceptionOrNull()` 显式判断，兼容所有版本。

## [0.1.0] — 2026-05-20

**首个完整发布。** 协议栈 v0.0.1–v0.0.9 已具备完整功能；本版本只增设置持久化与文档打磨，没有协议层改动。

### Added
- **设置持久化** (`util/SettingsStore.kt`)：用 SharedPreferences 记住上次的服务器 / 端口 / 用户名
  - **不持久化密码** —— 每次手动输入。考虑 PPTP 本身已弱，磁盘上明文 password 不是好主意
- UI 启动时自动加载上次配置；点 "连接 VPN" 时回写

### Documentation
- **README 重写**：架构图、构建步骤、测试用 PPTP 服务器搭建脚本（accel-ppp / poptop 二选一）、Wireshark 抓包提示、已知风险表、文件布局速览
- 路线图标记 v0.1.0 为首个完整发布

### Open Items (未来版本)
- NetworkCallback 监听底层变更，WiFi↔4G 切换自动重连
- Kill-switch：VPN 异常断开时仍阻止明文出口
- 多服务器配置管理
- 应用分流（per-app VPN routing）
- 真机互通性矩阵（accel-ppp / Windows RRAS / MikroTik）

## [0.0.9] — 2026-05-20

### Fixed / Improved
- **PAP / MS-CHAP-V2 不再无限等待**：双方各加 10–12s 超时 + 重传（PAP 最多 3 次），超时后失败回报。原先服务器丢包就永久挂起
- **LCP Configure-Reject 容错**：之前直接 close。现在解析被 reject 的 option 类型，把它从我们的提案里丢掉再重发；只有当所有 option 都被 reject 才放弃
- **VpnService `addDisallowedApplication(packageName)`**：把本应用自身从 VPN 路由表排除。否则 control TCP 1723 心跳的回包可能被路由到 TUN，造成自循环；libsu shell 子进程访问 helper UDS 也可能被影响
- **LCP Echo-Reply 修正**：移除一段 dead code，确保我们 reply 时填的是**我们自己的** Magic-Number（RFC 1661 §5.8），而不是 peer 的
- **认证状态机 race condition**：Success/Failure 路径全部 `compareAndSet(false, true)` 保护，并取消未触发的 timeoutJob，避免多线程下重复回调

### Known Untested Areas
- 没有真机测试反馈，下列场景仅按 RFC + pppd 源参考写就：
  - MikroTik / Windows RRAS / accel-ppp 三家服务器互通
  - 网络从 WiFi 切到蜂窝时的行为（理论上会断，v0.1.0 处理重连）
  - 实际 ping/HTTP 吞吐量
- 真机测一遍后所有"应该如何"会变成"实际如何"，到时再迭代

## [0.0.8] — 2026-05-20

### Added
- **RC4 流密码** (`ppp/Rc4.kt`) — 40 行纯 Kotlin 实现。理由：2026 Android 各 crypto provider 多数已移除 RC4；MPPE 在 stateless 模式下每包都要重新初始化 RC4，自实现避免 JCE 开销
- **MPPE 实现** (`ppp/Mppe.kt`) — RFC 3078 + RFC 3079
  - 128-bit stateless 模式（最常见，accel-ppp / Windows RRAS / MikroTik 默认）
  - `getAsymmetricStartKey()`：从 MS-CHAPv2 master key 派生不同方向的 send/recv 初始密钥（Magic-2 / Magic-3 字串）
  - `nextKey()`：每包轮键 SHA1(init || pad1 || curr || pad2) → RC4 自加密
  - `encrypt()` / `decrypt()`：实现 ABCD 标志位 + 12-bit coherency count；A bit (Flushed) 永远置位（stateless 要求）；D bit (Encrypted) 必须置位
  - coherency 跳跃自动追上（计算 delta，连续轮键追到当前 CC）
  - 注意：MPPE 把*内层 PPP 协议字段*一并加密，不只是 payload；外层 PPP 协议变为 0x00FD
- **CCP 状态机** (`ppp/CcpStateMachine.kt`) — RFC 1962 + RFC 3078
  - 复用 LcpPacket/LcpCodec 包结构；CCP 额外定义 code 14 (Reset-Request) / 15 (Reset-Ack)
  - 选项 18 (MPPE Supported Bits)：4 字节位掩码
  - 本端只提案 `MPPE-128 stateless`；对端不同提案直接重发；Reject 则放弃 → close
  - 支持 Reset-Request/Ack（本地解密失败时主动发，对端要求时回 Ack + 调用回调让上层 reset Mppe）
- **PptpSession 加密路径**：
  - 新增 Phase: `CcpNegotiating`
  - 认证成功后若 `mppeKeys` 存在（即 MS-CHAPv2 路径）启动 CCP；否则跳过（PAP 明文）
  - `sendIpv4()`：MPPE 激活后把 `[0x0021][IPv4 payload]` 整体喂给 `mppe.encrypt()`，外层协议改为 0x00FD
  - `handleMppeRx()`：解密 0x00FD 帧，剥出内层协议字段 (0x0021)，把 IPv4 包投递给 TUN
  - 解密失败（coherency gap 等）自动发 CCP Reset-Request
- UI: 加 "🔒 MPPE-128 stateless 已启用" 提示

### Notes
- v0.0.8 完成后**应能直连主流默认配置的 PPTP 服务器**（accel-ppp / Windows RRAS / MikroTik / poptop），无需关 MPPE
- PAP + MPPE 在协议上互斥（PAP 不产生 master key）。如果服务器用 PAP，链路保持明文（v0.0.7 行为）
- 40-bit / 56-bit MPPE 与 stateful 模式 v0.0.8 不支持；服务器要求时会 nak

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
