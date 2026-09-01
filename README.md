# RR Edge Hunter Android · CF 优选IP

> 📱 **Android 独立版** · 💻 [电脑端 RR Edge Hunter](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps 官方交流频道](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** 是一款在当前 Android 设备和当前网络上运行的 Cloudflare 入口 IP 优选工具。用户先粘贴一个当前在 V2rayNG 能用的 VMess/VLESS WebSocket + TLS Argo 节点；应用只在本机提取端口、SNI、Host 和 WS Path，立即丢弃 UUID。随后每轮从在线维护网段中随机生成 100 个地址，以 50 并发对每个地址执行三次 RTT + `CF-RAY` 验证，保留延迟最低的 10 个，再逐个做最多 5 秒真实下载。

达到期望带宽后，候选还必须使用原节点端口完成严格 TLS/SNI/Host 与标准 WebSocket `101` 握手；失败就继续找下一个。结果只显示通过这道节点路由复核的裸 IPv4 或 IPv6。把它填入原节点的 `address` 或 `server` 字段即可，其他参数全部保持不变。

> 结果只代表本轮设备、网络出口、运营商和时间。切换 Wi-Fi、移动数据、VPN、代理或网络出口后应重新测试。

## 安装

当前版本：**1.0.0**（`com.xiaowu7z.cfipoptimizer`）

[⬇️ **点击下载安装最新版 CF 优选IP（推荐）**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

这是一个通用 APK，不需要选择 CPU 架构。下载完成后按 Android 系统提示安装；如系统询问，请允许当前浏览器安装应用。

### Obtainium 自动更新

[🔄 **一键加入 Obtainium 自动更新**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.xiaowu7z.cfipoptimizer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Edge-Hunter-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22CF%20%E4%BC%98%E9%80%89IP%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Afalse%2C%5C%22releaseDateAsVersion%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5ECF-IP-Optimizer%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Afalse%7D%22%7D)

按钮使用 Obtainium 官方的 `obtainium://app/<URL 编码 JSON>` 完整配置深链，已经替用户固定包名、GitHub 来源、正式 APK 文件、按 Release 日期识别更新，并关闭预发布版与 CPU 架构筛选；即使应用版本按要求继续保持 1.0.0，仓库重发正式 Release 后也能识别更新。打开后确认添加即可。若浏览器或系统没有唤起 Obtainium，请打开 Obtainium，点击“添加应用”，把下面的仓库地址粘贴到“来源 URL”：

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

仓库只保留最新正式 APK；正式 Release 同时提供 `CF-IP-Optimizer.apk.sha256`。

## 一键默认值

| 项目 | 默认值 |
| --- | --- |
| IP 协议 | IPv4 |
| 期望带宽 | 100 Mbps |
| 测速流程 | 快速优选：100 IP → 三次 RTT → 最低延迟 10 个 → 首个达标即停 |
| 连接方式 | TLS 443（默认、严格证书校验）/ 非 TLS 80 |
| 测速地址 | 由公开维护接口动态下发；离线时使用缓存/官方备用 |
| 候选来源 | `baipiao.eu.org` 公开维护池；可叠加用户导入的安全公网 IP |
| 节点门禁 | 必须通过所粘贴节点的原端口、TLS SNI、HTTP Host 与 WS Path |
| 输出用途 | 只显示可通过节点路由握手的 IP；仅替换 `address/server` |

### 唯一测速模式

界面不再让普通用户在“均衡 / 亚洲狩猎 / 最大带宽”之间猜测。所有扫描统一使用一条可解释的快速流程：100 个随机候选、三次严格 RTT/CF-RAY 验证、最低延迟 10 个串行下载、首个达到目标即停止。未达到目标会自动进入下一轮，直到找到结果或用户点击停止。

每个下载候选最多测试 5 秒；只计算每个完整一秒窗口的速度并取峰值，所以小错误页和最后不足一秒的响应不会制造虚高。由于未达标会继续测试甚至换轮，应用不再展示虚假的“固定流量上限”。

## 工作方式

1. 从 `https://www.baipiao.eu.org/cloudflare/` 获取 IPv4/IPv6 网段、动态测速地址与数据中心表，成功数据在本机缓存 6 小时。
2. 每轮随机抽取最多 100 个网段：IPv4 保留前三段并随机最后一段；IPv6 保留前三个 hextet 并随机后五段。用户导入的安全公网 IP 会占用本轮一部分名额。
3. 以 50 并发对每个候选连续验证三次。单次包含 TCP 连接、可选 TLS 和 `Host: cloudflare.com` 请求；任一次失败或缺少 `CF-RAY` 即淘汰。
4. 按三次 TCP 延迟平均值升序，只保留前 10 个候选。
5. 按延迟顺序逐个连接动态测速主机，固定真实 TCP 目标为候选 IP；TLS 模式保留系统证书与 SNI/Host 校验，非 TLS 模式使用 80 端口。
6. 每个候选最多下载 5 秒，以 32 KiB 读取；每个完整一秒窗口计算一次 kB/s 峰值，最后不足一秒窗口不参与。
7. 达到 `期望 Mbps × 128 kB/s` 后，用候选 IP、原节点端口、SNI、Host 与 WS Path 执行 TLS + WebSocket `101` 路由握手；失败即淘汰。
8. 第一个同时通过速度和节点路由门禁的 IP 成为结果；否则继续下一个或自动进入新一轮。复制 IP 和 Cloudflare A/AAAA DNS-only 同步只对最终结果开放。

应用不测试或连接 VPS 源站 IP，但需要用户粘贴自己的现有节点，才能证明候选在 V2rayNG 所用端口、SNI、Host 与 WS Path 上可达。

## 自定义 IP 池

高级设置支持：

- 长复制 IPv4、IPv6、`IP:443`、`[IPv6]:443` 和 CIDR；
- TXT、CSV、TSV、JSON、Base64 文件；
- HTTPS IP 订阅链接。

导入 IP 不要求与动态测速域名当前 DNS 求交，也不要求预先属于 Cloudflare 官方 CIDR；私网、回环、链路本地、保留地址和错误协议族会被拒绝或忽略。外部公网候选只有通过同样的三次 RTT/CF-RAY 与真实下载门禁后才可复制或同步 DNS。文件选择使用 Android 系统选择器，不申请读取全部存储空间权限。

默认维护数据来自 [badafans/better-cloudflare-ip](https://github.com/badafans/better-cloudflare-ip) 所使用的公开接口。本项目只复现其公开描述与可观察的测速流程，代码为独立实现；上游仓库当前未声明开源许可证，因此没有复制或内嵌其源代码。

## V2rayNG 节点可用性门禁

Argo 复核现在是主流程，不再藏在高级设置里。首次测试先粘贴完整 `vmess://` 或 `vless://` 分享链接；当前支持 WebSocket + TLS 节点和 Cloudflare HTTPS 端口 `443/2053/2083/2087/2096/8443`。应用解析后立即清空输入框，不保存 UUID，也不把节点链接写入日志或历史。

这道门禁验证候选 IP 能否在原节点端口保持证书、TLS SNI、HTTP Host、真实 TCP 对端和 WS Path，并必须取得标准 WebSocket `101` 与正确 `Sec-WebSocket-Accept`。它不修改节点配置；最终仍只复制裸 IP。

## 可选：同步到 Cloudflare DNS

结果页可以把冠军 IP 写入自己指定的 Cloudflare DNS 记录。此功能默认关闭，普通测速与复制 IP 不需要 Cloudflare 凭据。

同步规则：

- IPv4 只写 `A`，IPv6 只写 `AAAA`；
- 强制使用 **DNS-only（灰云）**；
- 必须填写 32 位 **Zone ID** 和完整记录名 **FQDN**，例如 `edge.example.com`；不会自动猜 Zone；
- 只接受 Cloudflare API Token，不接受 Global API Key；最小权限为目标 Zone 的 **Zone / DNS / Edit**；
- 第一步“生成只读预览”不会写入；第二步必须再次确认，写入后会回读验证；
- 同名 CNAME、多个同类型记录或其他歧义会直接拒绝，不自动删除、合并或转换；
- Token 不进入测速日志、历史、导出或错误文本。

Android 默认只在当前应用会话内存中保留 Token。只有用户显式勾选“在本机安全保存 Token”时，才使用 Android Keystore 的 AES-GCM 加密保存；可随时从界面清除。Zone ID 和记录名可作为非秘密设置保留。

DNS 同步是独立可选输出，不会改变 Argo 域名或节点的端口、UUID、SNI、Host 与 Path。

## 安全与隐私

- 应用只申请联网和网络状态权限。
- 默认一键池使用公开维护接口，本机保留最近一次成功缓存；维护接口不可用且无缓存时回退 Cloudflare 官方网段。
- TLS 模式始终保留系统证书、SNI、Host 和实际远端验证；非 TLS 80 模式必须由用户主动选择。
- HTTPS 订阅限制公网目标、大小和跳转，并防护 DNS rebinding。
- API Token 不进入日志、历史和导出；持久保存必须由用户主动选择并由 Android Keystore 加密。
- 不提供端口扫描、漏洞探测、压力测试、任意 hosts/路由修改、系统代理配置或访问控制绕过。

详见 [SECURITY.md](SECURITY.md) 与 [NOTICE.md](NOTICE.md)。

## 构建与验证

要求 JDK 17 与 Android SDK 34：

```bash
./gradlew :logic-tests:check :app:lintDebug :app:assembleDebug
```

也可以运行：

```bash
./test.sh
./build.sh
```

正式签名从仓库外注入，详见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。当前版本保持 **1.0.0**。

Cloudflare、Android 等名称与商标归各自权利人所有；本项目是非官方独立工具。
