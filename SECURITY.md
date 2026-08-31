# 安全说明

请勿在 Issue、截图、日志、域名/IP 导入文件或订阅链接中公开个人网络信息、签名材料、节点配置或其他敏感数据。安全问题请通过 [RR-vps 官方交流频道](https://t.me/GMgP4NG7lncwZGE1) 与维护者联系，并先隐藏敏感信息。

## 测试授权与目标约束

本项目只接受对用户自有或明确获授权 Argo 节点的测试。每次任务必须先取得节点域名的当前 DNS 快照，确认它由 Cloudflare 代理并取得可信种子。Argo 优选模式可另外测试 Cloudflare 官方 CIDR 的受控抽样和用户导入候选。

- 自定义 IP、CIDR、文件和订阅无需与当前 DNS 求交，但必须属于 Cloudflare 官方 CIDR；非 Cloudflare 地址和无效协议族地址必须被拒绝。
- 每协议族候选数量、CIDR 抽样、并发和分层下载流量均有硬上限；探针入口会再次执行官方范围校验。
- 固定候选 IP 时必须继续使用用户 Argo 域名作为 SNI/Host，并保留系统 TLS 证书验证与真实 TCP 对端校验；不得关闭或绕开验证。
- 可选 WS Path 只有在随机 Key 的完整 HTTP 101、Upgrade、Connection 与 `Sec-WebSocket-Accept` 校验全部通过时才算兼容。
- 不接收任意强制路由、`hosts` 修改、任何 DNS 记录写入、代理、端口扫描、漏洞探测、压力测试或绕过访问控制相关功能请求。
- Android 端没有 DNS 凭据输入、存储或记录写入功能；优选结果只用于复制到 Argo 节点的 `address/server`，域名、SNI、Host 与 Path 保持原节点配置。

## 订阅与导入

订阅仅接受 HTTPS、公网目标、默认 443 端口及受限大小的文本内容。每次重定向都必须重新校验目标地址，并固定到本次校验通过的公网 IP，以降低 SSRF 和 DNS rebinding 风险。

## 正式签名

正式签名私钥只能保存在维护者控制的安全位置或 GitHub Actions Secrets 中。发布任务必须校验应用包名、版本标签、单调递增的 `versionCode`、v1/v2/v3 签名方案，以及与配置的新正式证书 SHA-256 指纹一致性。证书指纹、包名或 `versionCode` 不符合预期时必须阻止发布。详见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。

正式签名 Secrets 必须只存放于 GitHub 的 `android-release` 环境，并要求人工审批；`main` 与 `v*` 发布标签应启用保护规则，禁止强推和覆盖。
