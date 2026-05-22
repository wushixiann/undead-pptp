# PPTP Client for Android

为已 root 的 Android 设备实现的 PPTP VPN 客户端。
Android 12 (API 31) 起系统已移除 PPTP 支持，本项目在用户态恢复该能力。

**当前版本：v0.2.7（首页瘦身，删除调试入口与孤儿代码）**

✅ 实测打通：Android 14 (Pixel) + Magisk root + pptpd 服务器，能 ping baidu.com、走完整 IPv4 流量、MPPE-128 stateless 加密。

> ⚠️ PPTP 协议本身不安全（MS-CHAP-V2 已被破解，MPPE 弱）。本项目为可用性而生，不推荐用于传输敏感数据。

---

## 功能

- 完整的 PPTP 协议栈，纯用户态实现：
  - TCP 1723 控制通道（SCCRQ / OCRQ / Echo / StopCCRQ）
  - PPTP-Enhanced GRE 数据通道（K=1, Ver=1）
  - PPP 在 GRE 中（无 HDLC 帧）
  - LCP / IPCP / CCP 状态机
  - PAP 与 MS-CHAP-V2 认证
  - MPPE-128 stateless 加密
  - 自实现 MD4 + RC4，避开 2026 年 Android crypto provider 的覆盖空洞
- VpnService TUN 集成，全流量代理（已排除本应用自身）
- 单服务器配置持久化（用户名记住，密码每次重输）
- root 与 SELinux 兼容（实测 Magisk + Android 14 内核 6.1）

## 架构

```
                       Android App (UID 10274, untrusted_app)
   ┌─────────────────────────────────────────────────────────┐
   │  Compose UI                                              │
   │      ▲                                                   │
   │      │  状态                                              │
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

详见 `D:\work\CLAUDE.md` 旁边的 `pptp-ui-bubbly-grove.md` 实施计划。

## 构建

### 环境

| 工具 | 版本 |
|---|---|
| JDK | 17 (Temurin) |
| Android SDK | API 35 + build-tools 36+ |
| Android NDK | r27 (27.x) |
| Gradle | wrapper 8.11.1 (随仓库) |
| Kotlin / AGP | 2.1.0 / 8.7.3 |

最快安装：装 Android Studio（自带 JDK + SDK 管理器，从里面装 NDK）。

### 命令

```bash
cd D:\work\pptp
gradle wrapper                            # 第一次：生成 gradlew + .jar
.\gradlew :app:assembleDebug              # 编译
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 使用

1. 启动 app，首次会在 ① 区点 **检测 root + 原始 GRE socket** 按钮，Magisk 弹授权框，Grant 即可
   - 成功显示 `✅ raw GRE socket 已打开`，含 `id=0(root) context=u:r:magisk:s0` 之类诊断
   - 失败：按 UI 提示逐项排查（Magisk 装了没、给本应用授权没、SELinux 是否 Enforcing 干预 raw socket 等）
2. 在 ④ 区填写
   - **PPTP 服务器**：IP 或域名（域名会 DNS 解析为 IPv4）
   - **端口**：默认 1723
   - **用户名 / 密码**：服务器上的账号
3. 点 **连接 VPN** → 系统弹出 VPN 授权对话框，确认
4. 状态依次走 `ControlConnecting → CallSetup → BridgeStarting → LcpNegotiating → LcpOpen → Authenticating → Authenticated → CcpNegotiating → IpcpNegotiating → IpcpOpen → Connected`
5. UI 显示 `TUN 已建立：本端 X.X.X.X  对端 Y.Y.Y.Y` 与 `🔒 MPPE-128 stateless 已启用` 即成功
6. 系统通知栏出现 PPTP 前台服务通知；Android 设置 → 网络 → VPN 里可看到本 VPN 已激活

测试连通性：手机浏览器访问只能从 VPN 内部到达的资源；或 `ping` 测试机；或访问 `https://ifconfig.me` 看出口 IP 是否变成 PPTP 服务器侧。

## 测试用 PPTP 服务器（快速搭建）

```bash
# Linux: accel-ppp via Docker
docker run -d --name pptpd --restart=unless-stopped --privileged --net=host \
  -e PPTP_USER=test -e PPTP_PASS=test123 \
  ghcr.io/accel-ppp/accel-ppp:latest

# 或者 poptop:
sudo apt install pptpd
# /etc/ppp/chap-secrets:
#   test pptpd test123 *
# /etc/pptpd.conf:
#   localip 192.168.50.1
#   remoteip 192.168.50.10-100
sudo systemctl restart pptpd
```

抓包验证：
```bash
sudo tcpdump -i any -nn -w pptp.pcap '(tcp port 1723) or (proto gre)'
# 用 Wireshark 打开 pptp.pcap，自带 PPTP/PPP 解析
```

## 已知风险与限制

| 问题 | 说明 |
|---|---|
| **非 root 设备无法使用** | Android 安全模型阻止用户态应用获取 `CAP_NET_RAW`。这是平台限制，工程无法绕过 |
| **某些 ROM 即使 root 也拦 raw socket** | SELinux 策略过严的 ROM 上 v0.0.1 会直接失败。需要换 ROM 或额外的 `magiskpolicy --live` 规则 |
| **蜂窝网封 IP 协议 47** | 多数运营商默认黑洞 GRE。WiFi 通、4G/5G 不通的话十有八九是这个 |
| **NAT 后的 PPTP 服务器** | PPTP-GRE 的 NAT 穿透依赖特殊 NAT helper，Linux 客户端历史上脆弱，本客户端同理 |
| **网络切换不重连** | WiFi ↔ 4G 切换会断开。需要手动重连。下版本可能加 NetworkCallback 自动重连 |
| **PAP 不能配 MPPE** | PAP 不产生 master key；如果服务器只支持 PAP，链路明文。UI 不会阻止 |
| **stateful MPPE / 40-bit / 56-bit 不支持** | v0.0.8 只实现 stateless 128-bit，覆盖 99% 服务器默认配置 |

## 路线图

| 版本 | 里程碑 | 状态 |
|---|---|---|
| v0.0.1–v0.0.9 | 协议栈分层实现（控制 / PPP / Auth / IPCP / MPPE / interop） | ✅ |
| v0.1.0 | 设置持久化（首个完整发布） | ✅ |
| v0.1.1 | （内部测试） | ✅ |
| v0.1.2 | 16 KB page-size 对齐（Android 15 兼容） | ✅ |
| v0.1.3 | 蜂窝 GRE 诊断（TX/RX 计数 + iface 警告 + helper logcat） | ✅ |
| v0.1.4 | NetworkUtil 接口优先级选 WiFi（修复 SO_BINDTODEVICE 绑错） | ✅ |
| v0.1.5 | MPPE encrypt D bit + decrypt 按 CC 派生密钥 | ✅ |
| v0.1.6 | （误以为去掉 RC4 self-encrypt 是修，实际反了） | ⚠️ |
| **v0.1.7** | **MPPE 加 base key 阶段，与 pppd 算法对齐** | ✅ 实证打通 |
| v0.1.8 | VpnService setMetered(false) + 显式 /32 DNS 路由 | ✅ |
| v0.1.9–v0.2.5 | MPPE 算法迭代修正（PFC 内层 / MPPC C bit / 256-boundary 回滚 / 自动恢复尝试） | ✅ |
| v0.2.6 | stateless MPPE late-packet 丢弃 + flushed bit 强制 + 移除 CCP 自动 reset | ✅ |
| **v0.2.7** | **首页瘦身：删除调试 Section + 孤儿代码清理 + 文档更新** | ✅ 当前版本 |
| 未来 | 网络切换自动重连、kill-switch、多服务器配置 | — |

## 协议参考

- RFC 2637 (PPTP)
- RFC 1661 (PPP)
- RFC 1332 (IPCP)
- RFC 1334 (PAP)
- RFC 1962 (CCP)
- RFC 2759 (MS-CHAP-V2)
- RFC 3078 / 3079 (MPPE 与密钥派生)

## 文件布局速览

```
D:\work\pptp\
├── app/src/main/
│   ├── cpp/                           # native helper (root)
│   │   ├── helper_main.c              # probe/listen/bridge 三种模式
│   │   └── CMakeLists.txt
│   ├── kotlin/com/pptp/client/
│   │   ├── MainActivity.kt            # Compose UI 入口
│   │   ├── helper/                    # libsu + UDS bridge + FD 编解码
│   │   ├── ppp/                       # PPP 帧 + LCP/IPCP/CCP/Auth/MPPE 协议层
│   │   ├── pptp/                      # 控制通道 + GRE + PptpSession
│   │   ├── vpn/                       # VpnService + TunPipe + FGS 通知
│   │   └── util/                      # SettingsStore, NetworkUtil
│   ├── res/                           # strings.xml 等
│   └── AndroidManifest.xml
├── CHANGELOG.md
└── README.md (本文件)
```
