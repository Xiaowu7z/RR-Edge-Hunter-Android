# Android 更新日志

## 1.0.0

- 项目名称更新为 **RR Edge Hunter Android**，应用名称为 **CF 优选IP**。
- 应用包名更新为 `com.xiaowu7z.cfipoptimizer`，首个版本为 `1.0.0`。
- 优选对象由域名改为 IP：结果以 IPv4 或 IPv6 地址为主体，并保留来源、DNS 快照、协议族、POP 与多轮测试证据。
- 保留运营商模式（自动、移动、电信、联通）、IPv4 / IPv6 / 双栈、均衡模式与亚洲狩猎模式。
- 主流程改为 Argo 节点入口优选：推荐 IP 可直接填入节点 `address/server`，端口 443、原 Argo 域名 SNI/Host 与可选 WS Path 保持不变。
- 候选组合当前 DNS、Cloudflare 官方网段受控抽样和自定义 IP 池；导入 IP 不再强制与 DNS 求交，但必须属于官方 CIDR，并受 IPv4/IPv6 硬上限约束。
- 使用双主机验证：Argo 域名固定候选 IP 做证书、SNI/Host、trace 与可选 WS 101 验证；同一候选 IP 的吞吐使用 `speed.cloudflare.com` 公共测速端点。
- 亚洲狩猎改为稳定速度优先，POP 仅在同档成绩中加分；推荐项至少通过三轮 Full 中两轮。
- 保留“当前 DNS 体检”辅助模式，以及长复制、文件与 HTTPS 订阅导入。
- 保留 TLS SNI、Host 与证书校验；不增加任意强制路由、`hosts` 修改、任何 DNS 记录写入或代理能力。
- 不包含任何 DNS 记录写入；优选结果仅可复制和保存到本地历史。
- 建立新的正式签名占位与证书指纹验证流程；每个 Release 附带 APK 的 SHA-256 文件。
