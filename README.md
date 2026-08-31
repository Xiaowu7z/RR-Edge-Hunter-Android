# RR Edge Hunter Android · CF 优选IP

> 📱 **Android 独立版** · 💻 [RR Edge Hunter 电脑端](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps 官方交流频道](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** 是 RR Edge Hunter 家族的 Android 本机 Argo 入口优选工具。用户填写现有 Argo 节点域名，应用从当前 DNS、Cloudflare 官方网段受控抽样和可选自定义 IP 池中筛选入口，输出可直接填入节点 `address/server` 的 IPv4 或 IPv6；原 Argo 域名继续作为 TLS SNI 与 HTTP Host。

> 结果只代表本轮测试的设备、网络出口和时间。切换移动数据、Wi-Fi、运营商、VPN、代理或网络出口后，应重新测试。

## 安装

当前版本：**1.0.0**（`com.xiaowu7z.cfipoptimizer`）

[⬇️ **点击下载安装最新版 CF 优选IP（推荐）**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

这是一个通用 APK：点击下载，下载完成后按 Android 系统提示安装即可。无需选择 CPU 架构、Release 版本或任何高级选项。首次安装时，如系统询问，请允许你正在使用的浏览器安装应用。

### 测试通道

[🧪 **手动下载当前测试包**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/download/testing/CF-IP-Optimizer-testing.apk)

测试包与正式版暂时都保留版本号 **1.0.0**，因此自动更新工具不会把它识别为更高版本；需要手动下载并覆盖安装。测试通道使用相同的正式签名，但不会替换上面的正式版 `latest` 下载链接。

### 自动更新（可选）

[🔄 **已安装 Obtainium？点击开启自动更新**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Xiaowu7z/RR-Edge-Hunter-Android)

点击后确认添加 **CF 优选IP** 即可；不需要填写正则、CPU 架构、预发布、排序等选项。以后由 Obtainium 检查并下载新版本。

<details>
<summary>遇到问题再看：安装与校验</summary>

若自动更新链接未打开，请先安装 [Obtainium 官方最新版](https://github.com/ImranR98/Obtainium/releases/latest)，然后在“添加应用”中只粘贴下面的仓库地址：

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

每个正式版都提供 `CF-IP-Optimizer.apk.sha256` 校验文件；如需手动下载或校验，请查看 [全部 Releases](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases)。

</details>

## 主要功能

- 保留运营商模式：自动、移动、电信、联通。
- 保留 IP 协议族模式：IPv4、IPv6、双栈。
- 保留均衡模式与亚洲狩猎模式；成功率和稳定速度始终优先，HKG、NRT、SIN、ICN、TPE 等 POP 只在同档成绩中加分。
- 对候选 IP 进行 Pre、Micro、Full 分层测试，多轮复核成功率、延迟、TTFB、下载表现与波动；失败样本按 0 纳入排序。
- 一键优选组合当前 DNS、Cloudflare 官方 CIDR 受控抽样和自定义 IP；另保留“当前 DNS 体检”辅助模式。
- 自定义 IP 支持长复制、文件和 HTTPS 订阅，支持 IPv4、IPv6、CIDR、TXT、CSV、TSV、JSON 与 Base64。
- 每个候选都先用 Argo 域名做 TLS/SNI/Host、证书和 `/cdn-cgi/trace` 验证；填写 WS Path 时还必须通过完整 WebSocket 101 握手。
- 结果支持复制 IP 或完整填写摘要：地址/server、端口 443、SNI、Host 与 Path。
- 保存最近 50 条本地历史记录。

应用仅申请联网与网络状态权限。文件导入使用 Android 系统文件选择器，不申请读取全部存储空间的权限。

## IP 候选与测试边界

IP 优选不是对互联网上任意地址的扫描。每轮任务先取得**用户有权使用的 Argo 域名**当前 DNS 快照，确认它由 Cloudflare 代理并取得可信种子。主模式还会加入 Cloudflare 官方公布 CIDR 的小量确定性抽样和用户导入池，但每协议族均有硬上限。

- 导入 IP 不要求出现在 Argo 域名当前 DNS 中，但必须属于 Cloudflare 官方 CIDR；非 Cloudflare 地址会被拒绝。
- 当前 DNS、导入池和官方网段抽样最终仍受 IPv4 48 个、IPv6 24 个候选上限约束；长列表采用确定性分散抽样。
- IPv4 与 IPv6 分别校验和统计；双栈模式不会把一个协议族的结果冒充为另一个协议族的结论。
- 应用把 Argo 域名临时固定到每个候选 IP，但始终保留该域名的 SNI、Host 和系统 TLS 证书校验；不会关闭证书验证。
- Argo 域名通常没有 `speed.cloudflare.com` 的 `/__down`，所以入口兼容验证使用 Argo 域名，同 IP 的分层吞吐测试使用 Cloudflare 公开测速主机。结果是入口筛选证据，不等同于绕过客户端实测。
- 探测连接不继承系统 HTTP 代理，避免代理把实际 TCP 对端替换成其他地址；VPN 等网络出口变化仍会影响结果。

应用不提供也不执行任意强制路由、`hosts` 修改、任何 DNS 记录写入、代理配置、端口扫描、漏洞探测、压力测试或绕过访问控制。

## 自定义 IP 导入

展开“添加 / 管理 IP 池”后，可粘贴地址、导入本地文件或填写 IP 订阅链接。内容会统一规范化、按原顺序去重，并统计有效、忽略、IPv4、IPv6 和 CIDR 条目。

为避免无意扩大测试范围，CIDR 展开、总候选量、单文件大小和订阅大小都受限。订阅仅允许 **HTTPS** 公网目标和默认 `443` 端口；每次跳转均重新校验目标，并固定到本次已验证的公网 IP。

导入成功并不等于地址可测：地址还必须属于 Cloudflare 官方 CIDR、匹配所选协议族、通过 Argo 域名证书与真实远端校验，并在受控候选上限内入选。

## 构建与验证

要求 JDK 17 与 Android SDK 34。仓库自带 Gradle Wrapper：

```bash
./gradlew :logic-tests:check :app:lintDebug :app:assembleDebug
```

也可以使用：

```bash
./test.sh
./build.sh
```

`logic-tests` 运行不依赖公网状态的确定性 JVM 回归；真实 DNS、网络与取消时序测试应在受控环境或真机中单独执行。正式签名从仓库外注入，新证书占位、校验和 GitHub Actions 配置见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。

## 仓库结构

```text
app/           Android 应用源码与资源
logic-tests/   可复现的确定性 JVM 回归任务
test/          其余专项与网络环境测试
gradle/        Gradle Wrapper
```

电脑端项目位于 [RR-Edge-Hunter](https://github.com/Xiaowu7z/RR-Edge-Hunter)。

## 使用边界

本项目仅用于为用户自有或获明确授权的 Argo 节点评估 Cloudflare 官方网段入口。请遵守所在地法律、网络提供商政策和相关服务条款。

Cloudflare、Android 等名称与商标归各自权利人所有。本项目是非官方独立工具，与相关服务商不存在隶属、合作、赞助或背书关系。详见 [NOTICE.md](NOTICE.md)。
