# RR Edge Hunter Android · CF 优选IP

> 📱 **Android 独立版** · 💻 [电脑端 RR Edge Hunter](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps 官方交流频道](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** 是一款在当前 Android 设备和当前网络上运行的 Cloudflare 入口 IP 优选工具。默认不需要填写任何域名：应用先对官方网段候选做三次 TCP RTT 快筛，再把 `speed.cloudflare.com` 固定到入围 IP 的 `443` 端口，以严格 TLS 证书、SNI、Host、真实对端和 `CF-RAY` 校验执行约 1 秒真实下载，快速找到能直接填入节点且下载速度更高的入口。

优选结果是一个裸 IPv4 或 IPv6。把它填入 VMess / VLESS 等节点的 `address` 或 `server` 字段即可；节点原来的端口、UUID、协议、TLS SNI、HTTP Host、WS Path 等参数全部保持不变。

> 结果只代表本轮设备、网络出口、运营商和时间。切换 Wi-Fi、移动数据、VPN、代理或网络出口后应重新测试。

## 安装

当前版本：**1.0.0**（`com.xiaowu7z.cfipoptimizer`）

[⬇️ **点击下载安装最新版 CF 优选IP（推荐）**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

这是一个通用 APK，不需要选择 CPU 架构。下载完成后按 Android 系统提示安装；如系统询问，请允许当前浏览器安装应用。

### Obtainium 自动更新

[🔄 **一键加入 Obtainium 自动更新**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Xiaowu7z/RR-Edge-Hunter-Android)

按钮改用 Obtainium 官方的 `obtainium://add/<仓库地址>` 简单深链，不再传递 JSON、正则表达式或百分号编码，因此避开部分手机出现的 URI 编码错误。打开后确认添加即可；仓库只有一个正式 APK，Obtainium 会自动选择 **CF 优选IP**。若浏览器或系统没有唤起 Obtainium，请打开 Obtainium，点击“添加应用”，把下面的仓库地址粘贴到“来源 URL”：

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

仓库只保留最新正式 APK；正式 Release 同时提供 `CF-IP-Optimizer.apk.sha256`。

## 一键默认值

| 项目 | 默认值 |
| --- | --- |
| IP 协议 | IPv4 |
| 期望带宽 | 100 Mbps |
| 默认测速策略 | 亚洲狩猎 |
| 可选策略 | 均衡 / 亚洲狩猎 / 最大带宽 |
| 测速身份 | `speed.cloudflare.com:443` |
| 候选来源 | Cloudflare 官方默认池；可叠加用户导入的任意安全公网 IP |
| 输出用途 | 只替换节点 `address/server` |

### 三种测速策略

- **均衡**：从 20 个跨延迟分位候选中实测，连续两次达到目标带宽后提前结束，速度与流量消耗兼顾。
- **亚洲狩猎**：同样使用 20 个多样候选并支持达标早停；速度和稳定性优先，亚洲 POP 仅作同档加分。
- **最大带宽**：不提前停止，测试低 RTT 主体和跨延迟分位组成的 20 个候选，并确认最快 3 个；复测失败自动向下补位，适合寻找下载带宽最高的入口，会消耗更多流量。

三个策略均先从每族最多 100 个候选中进行三次 TCP connect 快筛，任一轮失败即淘汰，再让“低 RTT 主体 + 跨延迟分位”组成的 20 个候选进入严格下载验证，避免单一低延迟网段全部失败或漏掉更高吞吐线路。均衡与亚洲狩猎在某个 IP 连续两次达到目标带宽后提前结束；最大带宽测完整个候选集。最终推荐均须完成至少两次成功的严格下载样本。

## 工作方式

1. 获取 `speed.cloudflare.com` 当前 DNS 种子，并加载 Cloudflare 官方 CIDR 的每轮有界轮转抽样。
2. 如用户导入名单，将其中安全的公网字面量作为受限候选加入；私网、回环、链路本地和保留地址不会进入探测。
3. 固定 `speed.cloudflare.com:443` 到每个候选 IP，保留系统证书、SNI、Host 和实际 TCP 对端验证。
4. 对每族最多 100 个候选各做 3 次 TCP connect RTT 快筛；任一失败淘汰。Wi-Fi 并发最多 32，移动网络自动降到 16。
5. 三种模式都选择低 RTT 主体与跨延迟分位共 20 个，避免纯延迟截断和单一网段垄断候选。
6. 每个入围 IP 下载约 1 秒，只接受 2xx、严格 TLS、真实对端一致、有效 `CF-RAY` 且样本时长/大小合格的响应。
7. 达标候选二测失败会继续扫描；最终复测失败会从下一候选补位，直到获得规定数量的两次成功样本或候选耗尽。
8. 只有至少两次下载样本全部成功的 IP 才能复制到节点或同步 DNS；最大带宽按两次成功样本的实测平均下载速度选最快，其他模式优先可靠下限与稳定性。

默认流程测量当前手机网络到 Cloudflare 入口的质量，不需要 VPS 源站 IP，也不要求 Argo 域名。

## 自定义 IP 池

高级设置支持：

- 长复制 IPv4、IPv6、`IP:443`、`[IPv6]:443` 和 CIDR；
- TXT、CSV、TSV、JSON、Base64 文件；
- HTTPS IP 订阅链接。

导入 IP 不要求与 `speed.cloudflare.com` 当前 DNS 求交，也不要求预先属于 Cloudflare 官方 CIDR；私网、回环、链路本地、保留地址和错误协议族会被拒绝或忽略。外部公网候选只是受限输入，只有在 `speed.cloudflare.com:443` 上通过系统证书、SNI、Host、实际 TCP 对端、2xx、有效 `CF-RAY` 与两次足量/足时真实下载复测后，才会出现在可复制和可同步 DNS 的推荐榜。CIDR 抽样、每族最多 100 个候选、导入每族最多 60 个、并发和真实下载流量均有限制。文件选择使用 Android 系统选择器，不申请读取全部存储空间权限。

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
- 默认一键池仅来自 Cloudflare 官方网段；用户主动导入只接受安全公网字面量。探针不继承系统 HTTP 代理，外部候选未通过严格门禁时不可复制。
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
