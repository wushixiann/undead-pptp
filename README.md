# undead-pptp

> 用户态 PPTP VPN 客户端 — 让 Android 12+ 移除的 PPTP 在 root 设备上继续可用。

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

## 为什么有这个项目

Android 12 (2021) 起系统移除了 PPTP VPN 支持,理由是协议层不安全。但仍有些场景需要它:

- 连接只支持 PPTP 的家用路由器 / Mikrotik / Windows RRAS
- 把 PPTP 套在 WireGuard 等现代隧道内层使用,复用已有账号体系
- 学习 / 调试 PPTP 协议栈

本项目在用户态(Kotlin + 一个最小的 native helper)完整重新实现了 PPTP 客户端栈,不依赖任何 pppd / kernel ppp 模块,让无系统 PPTP 的 Android 也能拨号。

> ⚠️ **PPTP 协议本身有已知安全漏洞**(MS-CHAP-V2 离线破解 / MPPE 无完整性 / 控制信道明文)。**不要用于敏感数据或不可信网络**。详见 [安全说明](#安全说明)。

## 使用

**前置条件**: 已 root 的 Android 9+ 设备 (Magisk 推荐),可访问的 PPTP 服务器 (服务器需启用 MS-CHAP-V2 认证)。

1. 安装 APK
2. 启动 App,填写连接信息:
   - 服务器: IP 或域名
   - 端口: 默认 1723
   - 用户名 / 密码: 服务器上的账号
3. 点 **连接 VPN**:
   - 首次会请求 Magisk root 授权 → 选 "永久允许"
   - 系统弹出 VPN 授权对话框 → 同意
4. UI 显示 `TUN 已建立` 与 `🔒 MPPE-128 stateless 已启用` 即连接成功

### 故障排查

| 现象 | 可能原因 |
|---|---|
| 停在 `BridgeStarting` | Magisk 授权未给,或 SELinux 策略拒绝 raw socket(部分 ROM) |
| 停在 `LcpNegotiating` | 蜂窝网常封 GRE (IP 协议 47),切 WiFi 试试 |
| 认证失败 | 服务器是否启用了 MS-CHAP-V2 (本项目不支持 PAP) |
| `Connected` 几秒后断开 | 服务器侧 MPPE 不是 stateless 128-bit (大多数服务器默认就是) |

抓详细日志: `adb logcat -s PptpSession Mppe Lcp Ccp Ipcp UdsBridge HelperLifecycle`

## 安全说明

PPTP 协议本身存在已知漏洞,本项目作为客户端实现,**无法**也**不打算**修复协议层缺陷:

| 漏洞 | 实际意义 |
|---|---|
| MS-CHAP-V2 离线破解 | 抓到一次握手 + 云 GPU 一天内可还原 8-12 位口令密码 |
| MPPE 无完整性保护 | 加密不带 MAC/AEAD,密文位可翻转且接收方无感 |
| 无前向保密 | 密钥从长期口令派生,密码泄露 → 历史流量全部可解密 |
| 控制信道明文 | TCP 1723 上 LCP 协商完全明文,MITM 可改写 |
| GRE 数据通道无认证 | 知道 callId 即可注入伪造包 |

**适合使用的场景**:
- 内网协议穿透(外层套 WireGuard / Tailscale 等现代隧道)
- 连接遗留设备做内网管理(NAS / 家用路由器,内层协议是 HTTPS/SSH)

**不适合的场景**:
- 公共 WiFi 上保护银行 / 支付 / 邮箱
- 公司业务流量
- 作为暴露在公网的唯一加密层
- 国家级威胁模型

⚠️ **强烈建议:不要把 PPTP 服务器的 1723 端口直接对公网开放**。前面挡一层 WireGuard,App 先连 WireGuard 再在内层连 PPTP,实际安全性 = WireGuard 的安全性。

## License

[GPL-3.0](LICENSE) — 自由使用 / 修改 / 再分发,**派生作品必须以 GPL-3.0 兼容协议开源**。不允许把本项目代码塞进闭源 app 或商业 SDK。

## Disclaimer

本项目以学习、研究、个人使用为目的发布。

- 使用者应自行评估当地法律法规对 VPN 客户端使用的限制
- 协议层不安全(见 [安全说明](#安全说明)),作者不对任何形式的数据泄漏 / 滥用承担责任
- 软件以 "as is" 提供,无任何明示或暗示的担保
