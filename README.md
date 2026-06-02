# undead-pptp

> 用户态 PPTP VPN 客户端 — Android 12 宣告了 PPTP 的死亡,这里让它继续呼吸。
> A user-space PPTP VPN client for rooted Android 12+. Android killed PPTP. This brings it back.

[![Version](https://img.shields.io/badge/version-0.2.8-blue.svg)](CHANGELOG.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-28%2B-brightgreen.svg)](#)
[![Status](https://img.shields.io/badge/status-实证可用-brightgreen.svg)](#)

✅ 实测打通：Android 14 (Pixel) + Magisk root + pptpd 服务器 — 能 ping baidu.com、完整 IPv4 流量、MPPE-128 stateless 加密、长跑数小时不掉。

> ⚠️ **安全声明**：PPTP 协议本身已不安全（MS-CHAP-V2 离线破解、MPPE 无完整性、控制信道明文）。本项目仅恢复"协议能用"，不为安全背书。**不要用于敏感数据传输、不可信网络、或暴露在公网的 PPTP 服务器**。详见 [安全说明](#安全说明)。

---

## 为什么有这个项目

Android 12 (API 31, 2021) 系统移除 PPTP VPN 支持，理由是协议层不安全。这是正确的安全决定，但对以下场景仍有缺口：

- 连接遗留的 Mikrotik / Windows RRAS / 家用路由器 PPTP 服务（很多 SOHO/家庭设备只支持 PPTP）
- 把 PPTP 套在 WireGuard / Tailscale 内层使用（外层现代加密，内层 PPTP 复用现有账号体系）
- 学习/调试 PPTP 协议栈实现

本项目用 Kotlin + Compose UI + 一个最小化 native helper（仅 raw GRE socket）在用户态完整实现了 PPTP 客户端栈，所有协议层（控制 / 数据 / 认证 / 加密）都是自己写的，没有依赖任何 pppd / kernel ppp 模块。

## 功能

- **完整 PPTP 协议栈**（RFC 2637 / RFC 1661 等），纯用户态实现：
  - TCP 1723 控制通道（SCCRQ / OCRQ / Echo / StopCCRQ）
  - PPTP-Enhanced GRE 数据通道（K=1, Ver=1）
  - PPP-in-GRE（无 HDLC 帧）
  - LCP / IPCP / CCP 状态机
  - **MS-CHAP-V2** 认证（PAP 已被有意拒绝以防降级攻击，见 v0.2.8）
  - **MPPE-128 stateless** 加密，逐行对照 Linux kernel `drivers/net/ppp/ppp_mppe.c`
  - 自实现 MD4 / RC4，避开 Android crypto provider 在 2026+ 对老算法的下架
- **VpnService TUN 集成**，全流量代理（已排除本应用自身防止环回）
- **配置持久化**：用户名记住，密码每次重输
- **Root 与 SELinux 兼容**：实测 Magisk + Android 14 内核 6.1

## 架构

```
                       Android App (UID 10xxx, untrusted_app)
   ┌─────────────────────────────────────────────────────────┐
   │  Compose UI                                              │
   │      ▲                                                   │
   │      │ 状态                                                │
   │  ┌───┴──────────────────┐                                │
   │  │   PptpVpnService     │ ◀── Intent (start/stop)        │
   │  │   ├ TUN fd           │                                │
   │  │   ├ TunPipe          │                                │
   │  │   └ PptpSession      │                                │
   │  │      ├ ControlChannel│── TCP 1723 (protected) ────▶   │
   │  │      ├ UdsBridge ──┐ │                                │
   │  │      ├ LCP / IPCP / CCP / Auth / MPPE                 │
   │  │      └ PPP/GRE codec                                  │
   │  └────────────┬─────────┘                                │
   │               │ Unix Domain Socket (abstract namespace)   │
   └───────────────┼──────────────────────────────────────────┘
                   │
   ┌───────────────┼──────────────────────────────────────────┐
   │  pptp_helper (UID 0, magisk:s0 via `su -c`)              │
   │     ├ raw socket(AF_INET, SOCK_RAW, IPPROTO_GRE)         │
   │     ├ SO_BINDTODEVICE = underlay iface                   │
   │     └ poll() — UDS ↔ raw socket 双向桥接                  │
   └──────────────────────────────────────────────────────────┘
```

**为什么需要 root**：Android 安全模型禁止用户态应用获取 `CAP_NET_RAW`，而 PPTP 数据面用的 GRE（IP 协议号 47）必须用 raw socket 发送。我们把这部分逻辑拆到一个**最小化** native helper（约 250 行 C），通过 `su -c` 启动，App 和 helper 之间用 Unix domain socket 通信。这样除了一个 raw socket，root 进程不接触任何业务逻辑、不持久化任何数据。

## 构建

### 环境

| 工具 | 版本 |
|---|---|
| JDK | 17 (Temurin / 自带 Android Studio JBR 均可) |
| Android SDK | API 35 + build-tools 36+ |
| Android NDK | r27 (27.x) |
| Gradle | wrapper 8.11.1（随仓库） |
| Kotlin / AGP | 2.1.0 / 8.7.3 |

最快安装方式：用 Android Studio 打开本仓库，SDK Manager 里勾上 NDK，Sync Gradle 后即可。

### 命令行编译

```bash
git clone https://github.com/<your-username>/undead-pptp.git
cd undead-pptp
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release 签名（可选，仅"正式发布"需要）

仓库默认不带 release keystore — release APK 是发布者的身份证明，**绝不进 git**。如果你只是想本地装 release build 跑跑：

```bash
./gradlew :app:assembleRelease    # 会用 debug 签名自动 fallback，能装能跑
```

如果你要做真正的发布（升级现有 app、上 GitHub Release、上 F-Droid 等）需要自己的 keystore：

```bash
# 1. 生成 keystore（一次性，存好别丢 — 丢了这个 applicationId 就再也升不了级）
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 4096 \
        -validity 36500 -alias pptp-release

# 2. 复制示例配置并填入真实路径和密码
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties 填入 storeFile / storePassword / keyAlias / keyPassword

# 3. 构建
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` 和 `*.jks` `*.keystore` 都在 `.gitignore` 里，不会被意外提交。建议把 `release.jks` 放到 repo 之外的私密位置（如 `~/keys/pptp-release.jks`），并备份到加密 U 盘 / 密码管理器附件。**丢失 keystore = 这个 app 永远无法发布升级**（Android 拒绝替换签名）。

## 使用

> 前置条件：已 root 的 Android 设备（Magisk 21+ 推荐），可访问的 PPTP 服务器。

1. 安装并启动 App
2. 填写连接信息：
   - **PPTP 服务器**：IP 或域名（域名会被解析为 IPv4）
   - **端口**：默认 1723
   - **用户名 / 密码**：服务器上的账号（服务器需启用 MS-CHAP-V2 认证）
3. 点 **连接 VPN**：
   - 首次会触发 Magisk root 授权弹窗 → 选 **永久允许**
   - 系统弹出 VPN 授权对话框 → 同意
4. 状态依次走 `ControlConnecting → CallSetup → BridgeStarting → LcpNegotiating → LcpOpen → Authenticating → Authenticated → CcpNegotiating → IpcpNegotiating → IpcpOpen → Connected`
5. UI 显示 `TUN 已建立：本端 X.X.X.X  对端 Y.Y.Y.Y` 和 `🔒 MPPE-128 stateless 已启用` 即成功
6. 系统通知栏出现 PPTP 前台服务通知；系统设置 → 网络 → VPN 里可看到本 VPN 已激活

### 测试连通性

```bash
# 出口 IP 是否变成 PPTP 服务器侧
curl https://ifconfig.me

# 域名解析是否走 VPN 下发的 DNS
nslookup baidu.com
```

或者直接打开浏览器访问只能从 VPN 内部到达的资源。

### 故障排查

| 现象 | 可能原因 |
|---|---|
| 一直停在 `BridgeStarting` | Magisk 授权未给，或 SELinux 策略拒绝 raw socket（部分 ROM） |
| `BridgeStarting` 后 `LcpNegotiating` 停住 | 蜂窝网封 IP 协议 47（GRE）。切 WiFi 试试。日志会显示 `iface=rmnet*` ⚠️ |
| 认证失败 | 服务器是否启用 MS-CHAP-V2？v0.2.8 起客户端**拒绝 PAP** 防降级攻击 |
| `Connected` 几秒后掉 | 服务器侧 MPPE 配置不是 stateless 128-bit（accel-ppp / pptpd 默认是） |

具体日志：`adb logcat -s PptpSession Mppe Lcp Ccp Ipcp UdsBridge HelperLifecycle`

## 自建测试 PPTP 服务器

```bash
# Debian/Ubuntu poptop
sudo apt install pptpd
sudo tee -a /etc/ppp/chap-secrets <<'EOF'
test pptpd test123 *
EOF
sudo tee /etc/pptpd.conf <<'EOF'
localip 192.168.50.1
remoteip 192.168.50.10-100
EOF
sudo systemctl restart pptpd

# 抓包验证
sudo tcpdump -i any -nn -w pptp.pcap '(tcp port 1723) or (proto gre)'
# Wireshark 打开 pptp.pcap，自带 PPTP/PPP 解析
```

⚠️ **不要把 1723 直接对公网开放**。即使密码强，pptpd 历史 CVE + MS-CHAP-V2 离线破解 + 协议层 MITM 共同作用下，对公网暴露 PPTP 端口 = 给攻击者递了一张"我家的门"告示。建议方案：

```
公网 ── WireGuard (UDP, 现代密码学) ──▶ 家里 ─── pptpd 仅监听内网 IP
```

App 先连 WireGuard，再在 WG 隧道内连 PPTP。

## 已知限制

| 问题 | 说明 |
|---|---|
| **非 root 设备无法使用** | Android 安全模型阻止用户态获取 `CAP_NET_RAW`，平台限制，无法绕过 |
| **某些 ROM SELinux 过严** | 即使 root 也可能拦截 raw socket，需要 ROM 级调整或 `magiskpolicy --live` |
| **蜂窝网常封 GRE** | 多数运营商默认黑洞 IP 协议 47，WiFi 通、4G/5G 不通的话基本是这个 |
| **NAT 后 PPTP 服务器** | PPTP-GRE 的 NAT 穿透依赖特殊 helper，部分中间网络设备实现脆弱 |
| **网络切换不重连** | WiFi ↔ 4G 切换会断开，需手动重连。未来版本可能加 NetworkCallback |
| **stateful MPPE / 40 / 56-bit 不支持** | 只实现 stateless 128-bit，覆盖 99% 服务器默认 |
| **IPv6 不支持** | inner IPCPv6 未实现，server 端推的 IPv6 包会被丢弃 |
| **仅支持 MS-CHAP-V2 认证** | PAP 自 v0.2.8 起被拒绝（防 MITM 降级到明文）。MS-CHAP-V1 / EAP 未实现 |

## 安全说明

PPTP 协议本身有以下**已知**漏洞，本项目无法、也不打算修复（这是协议层问题）：

| 漏洞 | 实际意义 |
|---|---|
| MS-CHAP-V2 离线破解 | 抓到一次握手 + 云 GPU < 1 天能还原 8-12 位口令的密码 |
| MPPE 无完整性保护 | 加密不带 MAC/AEAD，攻击者可翻转密文位且接收方无感 |
| 无前向保密（PFS） | 密钥从长期口令派生，密码泄露 → 所有历史流量可解密 |
| 控制信道明文 | TCP 1723 上 LCP 协商完全明文，MITM 可改写 |
| GRE 数据通道无认证 | 知道 callId（明文传）即可注入伪造 GRE 包 |

**适合的使用场景**：
- ✅ 内网协议穿透（外层套 WireGuard / Tailscale）
- ✅ 连接遗留设备做内网管理（NAS、家用路由器、IPC 等内层都是 HTTPS/SSH）
- ✅ 协议栈学习与调试

**不适合的场景**：
- ❌ 公共 WiFi 上保护银行支付
- ❌ 公司业务流量
- ❌ 暴露在公网的服务器作为唯一加密层
- ❌ 国家级威胁模型

详细漏洞分析与缓解措施见 [CHANGELOG](CHANGELOG.md) 历次安全章节。

## 路线图

最近几个里程碑：

| 版本 | 关键变更 |
|---|---|
| **v0.2.8** | LCP 强制 MS-CHAP-V2、outbound 队列扩容、CDN 即时上抛 |
| v0.2.7 | 首页瘦身：删除调试 Section + 孤儿代码清理 |
| v0.2.6 | stateless MPPE late-packet 丢弃（修长跑解密漂移）|
| v0.1.7 | MPPE 加 base key 阶段，与 pppd 算法对齐 — **首个实证可用版本** |
| v0.0.x | 协议栈分层实现（控制 / PPP / Auth / IPCP / MPPE / interop） |

完整版本历史与详细技术决策见 [CHANGELOG.md](CHANGELOG.md)。

**未来计划**：

- 网络切换自动重连（NetworkCallback 监听 WiFi ↔ 蜂窝）
- Kill switch（VPN 断时禁止裸流量）
- 多服务器配置
- 可选的"允许 PAP"高级选项（带显著安全警告）

## 协议参考

- RFC 2637 (PPTP)
- RFC 1661 (PPP)
- RFC 1332 (IPCP)
- RFC 1962 (CCP)
- RFC 2759 (MS-CHAP-V2)
- RFC 3078 / 3079 (MPPE 与密钥派生)

实现细节大量参考了 Linux kernel `drivers/net/ppp/ppp_mppe.c` 与 pppd 源码——特别是 MPPE 密钥派生 / late-packet 丢弃 / coherency count 处理。

## 项目布局

```
.
├── app/src/main/
│   ├── cpp/                           # native helper (root)
│   │   ├── helper_main.c              # probe/bridge 模式
│   │   └── CMakeLists.txt
│   ├── kotlin/me/jinsei/pptp/
│   │   ├── MainActivity.kt            # Compose UI 入口
│   │   ├── helper/                    # libsu + UDS bridge + 帧编解码
│   │   ├── ppp/                       # PPP 帧 + LCP/IPCP/CCP/Auth/MPPE
│   │   ├── pptp/                      # 控制通道 + GRE + PptpSession
│   │   ├── vpn/                       # VpnService + TunPipe + 前台通知
│   │   └── util/                      # SettingsStore, NetworkUtil
│   ├── res/
│   └── AndroidManifest.xml
├── CHANGELOG.md
├── LICENSE
└── README.md
```

## 贡献

欢迎 issue 与 PR。提交前请：

1. 阅读 [CHANGELOG.md](CHANGELOG.md) 了解协议栈实现历史与已踩过的坑
2. 改 protocol-level 逻辑请对照 RFC 与 Linux kernel `ppp_mppe.c` 验证
3. 改 UI / 配置 / 文档请保持现有的"诊断优先"风格——出错时让用户能看到具体哪一层断了

## License

**GNU General Public License v3.0** — 见 [LICENSE](LICENSE)。

简单说：
- ✅ 自由使用、修改、再分发
- ✅ 商业使用（包括收费）
- ⚠️ **派生作品必须以 GPL-3.0 兼容协议开源**（强 copyleft）— 不能把本项目代码塞进闭源 app 或商业 SDK
- ⚠️ 修改后必须保留版权声明并标明你的修改

选择 GPL-3.0 的明确意图：欢迎社区改进与自由使用，但**不允许任何形式的闭源重打包、闭源商业分发**。如果你希望用本项目代码做闭源衍生，请勿提交 PR（贡献会被视为同意以 GPL-3.0 贡献），并自行评估法律风险。

## Disclaimer

本项目以**学习、研究、个人使用**为目的发布。

- 使用者应自行评估当地法律法规对 VPN 客户端使用的限制
- 协议层已知不安全（见 [安全说明](#安全说明)），作者不对任何形式的数据泄漏、滥用、滥联承担责任
- 不为任何形式的非法访问、绕过 GFW、规避审查行为背书
- 软件以"as is"提供，无任何明示或暗示的担保
