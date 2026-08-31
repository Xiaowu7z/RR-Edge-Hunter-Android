# RR Edge Hunter Android · CF 优选IP

> 📱 **Android 独立版** · 💻 [RR Edge Hunter 电脑端](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps 官方交流频道](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** 是 RR Edge Hunter 家族的 Android 本机 IP 质量评估工具。它在当前设备和当前网络上，对已获授权测试主机的 DNS 实际分配 Cloudflare IP 做分层探测、复核和排序，帮助选择更稳定、更适合当前网络的 IPv4 或 IPv6 地址。

> 结果只代表本轮测试的设备、网络出口和时间。切换移动数据、Wi-Fi、运营商、VPN、代理或网络出口后，应重新测试。

## 安装与自动更新

当前版本：**1.0.0**（`com.xiaowu7z.cfipoptimizer`）

- [一键加入 Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Xiaowu7z/RR-Edge-Hunter-Android)
- [查看 Android Releases](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases)

### Obtainium 添加方法

1. 安装 [Obtainium 官方最新版本](https://github.com/ImranR98/Obtainium/releases/latest)。大多数新款安卓手机选择 `app-arm64-v8a-release.apk`；不确定处理器架构时选择通用版 `app-release.apk`。
2. 点击上方“**一键加入 Obtainium**”，选择用 Obtainium 打开，确认添加 **CF 优选IP**。
3. 首次安装或更新时，按 Android 提示允许 Obtainium 安装未知应用；之后在 Obtainium 中检查更新即可。

若一键链接未自动打开，请在 Obtainium 中选择“添加应用”并粘贴：

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

每个正式 APK 都附带同名 `.sha256` 校验文件；请以对应 GitHub Release 的校验值为准。

## 主要功能

- 保留运营商模式：自动、移动、电信、联通。
- 保留 IP 协议族模式：IPv4、IPv6、双栈。
- 保留均衡模式与亚洲狩猎模式；亚洲狩猎以 HKG、NRT、SIN、ICN、TPE 等实测 POP 为排序参考。
- 对候选 IP 进行 Pre、Micro、Full 分层测试，多轮复核成功率、延迟、TTFB、下载表现与波动；失败样本按 0 纳入排序。
- 当前 DNS 快照与自定义 IP 输入、文件、订阅交集筛选；支持 IPv4、IPv6、CIDR 与常见 TXT、CSV、TSV、JSON、Base64 内容格式。
- 显示 IPv4 / IPv6、来源、DNS 快照、POP 和可复核的结果证据；支持复制。
- 保存最近 50 条本地历史记录。

应用仅申请联网与网络状态权限。文件导入使用 Android 系统文件选择器，不申请读取全部存储空间的权限。

## IP 候选与测试边界

IP 优选不是对互联网上任意地址的扫描。每轮任务会先取得**用户有权测试主机**的当前 DNS 快照，并以该快照中实际分配的 Cloudflare IP 作为可测试集合。

- 内置候选只在当前 DNS 快照与 Cloudflare 合法范围同时满足时进入测试。
- 自定义 IP、CIDR、文件或订阅导入的地址，必须与当前 DNS 实际解析结果求交集；不在交集中的地址会被拒绝，而不是被强行连接。
- IPv4 与 IPv6 分别校验和统计；双栈模式不会把一个协议族的结果冒充为另一个协议族的结论。
- 测试保留 SNI、Host 和 TLS 证书校验，不关闭证书校验，也不把任意地址伪装成已授权主机。
- 探测连接不继承系统 HTTP 代理，避免代理把实际 TCP 对端替换成其他地址；VPN 等网络出口变化仍会影响结果。

应用不提供也不执行任意强制路由、`hosts` 修改、任何 DNS 记录写入、代理配置、端口扫描、漏洞探测、压力测试或绕过访问控制。

## 自定义 IP 导入

选择“导入列表交集”后，可粘贴地址，导入本地文件，或填写 IP 订阅链接。内容会统一规范化、按原顺序去重，并统计有效、忽略、IPv4、IPv6 和 CIDR 条目。

为避免无意扩大测试范围，CIDR 展开、总候选量、单文件大小和订阅大小都受限。订阅仅允许 **HTTPS** 公网目标和默认 `443` 端口；每次跳转均重新校验目标，并固定到本次已验证的公网 IP。

导入成功并不等于地址可测：所有自定义条目仍要与本轮 DNS 快照求交集，且必须属于允许的 Cloudflare IP 范围。

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

本项目仅用于在自有或获明确授权的主机与网络上评估当前 DNS 实际分配的 Cloudflare IP。请遵守所在地法律、网络提供商政策和相关服务条款。

Cloudflare、Android 等名称与商标归各自权利人所有。本项目是非官方独立工具，与相关服务商不存在隶属、合作、赞助或背书关系。详见 [NOTICE.md](NOTICE.md)。
