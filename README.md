# RR Edge Hunter Android · CF 优选IP

> 📱 **Android 独立版** · 💻 [电脑端 RR Edge Hunter](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps 官方交流频道](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** 是一款在当前 Android 设备和当前网络上运行的 Cloudflare 入口 IP 优选工具。默认不需要填写任何域名：应用把 `speed.cloudflare.com` 固定到每个候选 IP 的 `443` 端口，保留严格 TLS 证书、SNI、Host 与真实对端校验，再通过分层、多轮真实下载寻找更快、更稳定的入口。

优选结果是一个裸 IPv4 或 IPv6。把它填入 VMess / VLESS 等节点的 `address` 或 `server` 字段即可；节点原来的端口、UUID、协议、TLS SNI、HTTP Host、WS Path 等参数全部保持不变。

> 结果只代表本轮设备、网络出口、运营商和时间。切换 Wi-Fi、移动数据、VPN、代理或网络出口后应重新测试。

## 安装

当前版本：**1.0.0**（`com.xiaowu7z.cfipoptimizer`）

[⬇️ **点击下载安装最新版 CF 优选IP（推荐）**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

这是一个通用 APK，不需要选择 CPU 架构。下载完成后按 Android 系统提示安装；如系统询问，请允许当前浏览器安装应用。

### Obtainium 自动更新

[🔄 **一键加入 Obtainium 自动更新**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.xiaowu7z.cfipoptimizer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Edge-Hunter-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22CF%20%E4%BC%98%E9%80%89IP%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22%5ECF-IP-Optimizer%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22includePrereleases%5C%22%3Afalse%7D%22%2C%22overrideSource%22%3Anull%7D)

按钮已经预设正式 Release、唯一 APK 文件和通用架构。打开后只需确认添加 **CF 优选IP**，不要修改高级选项。若浏览器或系统没有唤起 Obtainium，请打开 Obtainium，点击“添加应用”，把下面的仓库地址粘贴到“来源 URL”：

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

仓库只保留最新正式 APK；正式 Release 同时提供 `CF-IP-Optimizer.apk.sha256`。

## 一键默认值

| 项目 | 默认值 |
| --- | --- |
| IP 协议 | IPv4 |
| 期望带宽 | 100 Mbps |
| 测速策略 | 亚洲狩猎 |
| 测速身份 | `speed.cloudflare.com:443` |
| 候选来源 | Cloudflare 官方池；可叠加用户导入的官方 IP |
| 输出用途 | 只替换节点 `address/server` |

亚洲狩猎仍以成功率、复核底线、最低与平均吞吐和波动为主；HKG、NRT、SIN、ICN、TPE 等 POP 只在同档成绩中加分。

## 工作方式

1. 获取 `speed.cloudflare.com` 当前 DNS 种子，并加载 Cloudflare 官方 CIDR 的确定性受控抽样。
2. 如用户导入名单，将其中属于 Cloudflare 官方网段的地址加入候选。
3. 固定 `speed.cloudflare.com:443` 到每个候选 IP，保留系统证书、SNI、Host 和实际 TCP 对端验证。
4. 执行 Pre 快筛、Micro 复核和多轮 Full 下载；失败轮次按 `0 Mbps` 纳入成功率和稳定性。
5. 按复核底线、成功率、最低/平均吞吐、波动和 TTFB 排名，并标注是否达到设定带宽。

默认流程测量当前手机网络到 Cloudflare 入口的质量，不需要 VPS 源站 IP，也不要求 Argo 域名。

## 自定义 IP 池

高级设置支持：

- 长复制 IPv4、IPv6、`IP:443`、`[IPv6]:443` 和 CIDR；
- TXT、CSV、TSV、JSON、Base64 文件；
- HTTPS IP 订阅链接。

导入 IP 不要求与 `speed.cloudflare.com` 当前 DNS 求交，但必须属于 Cloudflare 官方 CIDR。私网、回环、链路本地、保留地址、非 CF 地址和错误协议族会被拒绝或忽略；CIDR 抽样、候选量、并发和真实下载流量均有限制。文件选择使用 Android 系统选择器，不申请读取全部存储空间权限。

第三方非官方反代不会混入默认官方池。

## 高级：Argo 兼容复核

普通优选不需要域名。只有希望确认候选 IP 是否兼容自己的 Argo 节点时，才在高级设置中开启，并填写原节点域名、TLS 端口和可选 WS Path。

开启后，候选除公共测速外还必须使用原域名完成证书、SNI、Host 与真实对端校验；填写 Path 时必须通过标准 WebSocket `101` 握手。它只是附加复核，最终仍只复制裸 IP，节点端口、UUID、SNI、Host 与 Path 不会被工具改写。

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
- 默认仅连接 Cloudflare 官方网段候选，探针不继承系统 HTTP 代理。
- TLS 证书、SNI、Host 和实际远端验证始终启用。
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
