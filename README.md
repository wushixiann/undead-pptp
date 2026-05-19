# PPTP Client for Android

为已 root 的 Android 设备实现的 PPTP VPN 客户端。Android 12 (API 31) 起系统已移除 PPTP 支持，本项目目标是在用户态恢复该能力。

**当前版本：v0.0.3** — App ↔ Helper UDS 桥

> ⚠️ PPTP 协议本身不安全（MS-CHAPv2 已被破解，MPPE 弱）。本项目为可用性而生，不推荐用于传输敏感数据。

---

## 当前里程碑（v0.0.1）

只交付一件事：在你的 root 设备上能否成功调用
```c
socket(AF_INET, SOCK_RAW, IPPROTO_GRE);
setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE, "wlan0", ...);
```

**这是整个项目的可行性前提**。若失败，要么换 ROM/root 方案，要么放弃。后续里程碑（PPTP 控制通道、PPP、MPPE 等）全部依赖此能力。

---

## 路线图

| 版本 | 里程碑 |
|---|---|
| v0.0.1 ✅ | 项目骨架 + helper 源码 |
| v0.0.2 ✅ | 修复 libsu 根权限检测时序 |
| v0.0.3 ✅ | App ↔ helper UDS 桥（本版本） |
| v0.0.4 | PPTP 控制通道 (TCP 1723) |
| v0.0.5 | LCP 协商 |
| v0.0.6 | PAP / MS-CHAPv2 认证 |
| v0.0.7 | IPCP + VpnService TUN |
| v0.0.8 | MPPE-128 stateless |
| v0.0.9 | 多服务器互通测试 |
| v0.1.0 | 生命周期 + UI 打磨 |

---

## 构建要求

### 必备（当前未安装的项已标注）

| 工具 | 版本 | 状态 |
|---|---|---|
| JDK | 17 或 21 | ❌ 需安装（推荐 Temurin 17） |
| Android SDK | API 35 (Android 15) | ✅ 已有（build-tools 37.0.0） |
| Android NDK | r27（即 27.x） | ❌ 需通过 SDK Manager 安装 |
| Gradle | 由 wrapper 自动下载 8.11.1 | ✅ wrapper 已配置 |

### 推荐安装路径

**方式 A（推荐）：装 Android Studio**
一次性带 JDK + Android SDK 管理器（可装 NDK）。下载 [Android Studio](https://developer.android.com/studio)。

**方式 B：仅 CLI**
1. 装 [Temurin JDK 17](https://adoptium.net/zh-CN/temurin/releases/?version=17)
2. 设置环境变量 `JAVA_HOME` 与 `ANDROID_HOME=C:\Users\<you>\AppData\Local\Android\Sdk`
3. 用现有 SDK 装 cmdline-tools，然后：
   ```
   sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
   ```

---

## 构建与安装

```bash
# 在项目根目录
./gradlew :app:assembleDebug

# 输出: app/build/outputs/apk/debug/app-debug.apk

# 通过 adb 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 手工测试 helper（不经 app）

```bash
# 1. 找出已安装的 helper 路径
adb shell run-as com.pptp.client readlink /data/data/com.pptp.client/lib
# 例如输出 /data/app/~~xxx/com.pptp.client-yyy/lib/arm64

# 2. 用 su 调用 probe 模式（注意：执行路径，不是 run-as）
adb shell
su
/data/app/~~xxx/com.pptp.client-yyy/lib/arm64/libpptp_helper.so probe wlan0
# 期望输出：OK wlan0

# 3. listen 模式 + 抓包验证（另起一个 adb shell）
su
/data/app/~~xxx/com.pptp.client-yyy/lib/arm64/libpptp_helper.so listen wlan0
# 与此同时从一台远端发 GRE 包到本机（或连接一个真实 PPTP 服务器）
# helper stderr 应打印来源 IP + 包内容前 32 字节
```

### App 内测试

#### ① root + raw GRE 自检（v0.0.1/v0.0.2 验证项）

1. 启动 app
2. 点击 **检测 root + 原始 GRE socket** 按钮
3. **首次点击时 Magisk 应弹出授权请求**，点 Grant
4. 期望：`raw GRE socket 已打开（已绑定 wlan0）` + 诊断行

#### ② UDS bridge 测试（v0.0.3 新增）

前置：① 通过。

1. 在网内准备一台测试机（Linux/Mac），运行 `sudo tcpdump -i any -nn proto gre`
2. 点击 **启动 bridge**，状态应从 `WaitingForHelper` → `Connected`
3. **测试目标 IP** 填入测试机的内网 IP
4. 点击 **发送测试 GRE 包**
5. 期望：
   - app 端 TX 计数 +1
   - 测试机 tcpdump 看到一条 GRE 包，长度 12B（GRE header 8B + "TEST" 4B），proto = 0x880B
6. 如果测试机回了一个 GRE 包（一般不会，但你可以手工发一个测试），app 的 RX 日志应该出现一行
7. 点击 **停止**，状态变 `Stopped`，helper 退出（"helper exit=0"）

如果状态卡在 `WaitingForHelper`：helper 未连入 UDS。
- 可能 helper 启动失败（`ERR socket` / `ERR bindtodevice`）—— 检查 "helper 输出" 区显示的退出码与 stderr
- 也可能抽象命名空间 socket 被 SELinux 拦截；后续版本会加文件系统 UDS 兜底

---

## 已知风险（v0.0.1 阶段）

1. **定制 ROM 的 SELinux 策略**可能完全拒绝 raw socket，即便 UID 0。这是项目能否继续的关键检查点。
2. **某些蜂窝运营商**直接黑洞 IP 协议 47 (GRE)，后续里程碑会受影响，但与 v0.0.1 无关。
3. **NDK 未装时无法编译 helper**，UI 仍可启动但点击按钮会报"helper 二进制不存在"。

---

## 协议参考

- RFC 2637 — PPTP
- RFC 1661 — PPP
- RFC 2759 — MS-CHAPv2
- RFC 3078 / 3079 — MPPE 与密钥派生
