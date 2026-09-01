# 安全说明 / Security Policy

请勿在公开 Issue、截图、日志、域名/IP 导入文件或订阅链接中公开 Cloudflare API Token、Zone ID 与域名组合、节点链接或个人网络信息。安全问题请使用 GitHub Private Vulnerability Reporting，或通过仓库列出的项目频道联系维护者。

## 测速边界

- 主流程要求用户粘贴一个自己已有且获授权的 VMess/VLESS WS+TLS 节点。应用只保留非秘密路由字段；完整链接与 UUID 在解析后立即丢弃，不进入日志或历史。
- 导入 IP 不必与动态测速域名当前 DNS 求交，也不要求预先属于 Cloudflare 官方 CIDR；非公网、私网、回环、链路本地、保留地址和错误协议族会被拒绝。
- 每轮最多 100 个候选、50 并发预检、延迟前 10 个逐个最多下载 5 秒。未达标会进入下一轮，因此总轮数与总流量由用户停止操作和实际网络结果决定。
- TLS 443 模式保留系统证书、SNI、Host 与实际 TCP 对端验证；非 TLS 80 必须由用户显式选择。探针不继承系统 HTTP 代理。
- 达标候选必须在原节点端口通过严格 TLS/SNI/Host、实际对端和 WebSocket 101 复核。最终仍只输出裸 IP，节点原端口、UUID、SNI、Host 与 Path 不变。

## 导入与本地数据

- 文件使用 Android 系统文件选择器，不申请读取全部存储空间权限。
- HTTPS 订阅仅接受公网目标、默认 443、有限跳转和有限响应大小，并逐跳重新验证，降低 SSRF 与 DNS rebinding 风险。
- 测速历史保存在应用本地；网络出口在测试中变化会使本轮结果作废。

## Cloudflare DNS 同步

DNS 写入默认关闭，必须由用户在结果页主动开启：

- 只允许将通过当前实时测速门禁的 IPv4 写入 `A`，或 IPv6 写入 `AAAA`；强制 DNS-only（灰云）。
- 32 位 Zone ID 和完整记录 FQDN 必填；不会自动猜测 Zone。
- 只接受目标 Zone **Zone / DNS / Edit** 最小权限的 API Token，不接受 Global API Key。
- 写入采用“只读预览 → 用户明确确认 → 写入后回读验证”。预览后状态变化必须重新预览。
- 同名 CNAME、多个同类型记录或其他歧义状态一律拒绝；不会自动删除、合并或转换记录。
- Token 不进入测速日志、历史、导出、异常文本或崩溃信息。
- Token 默认只存在当前会话内存。只有用户显式勾选保存时，才使用 Android Keystore 管理的 AES-GCM 密钥加密持久化；用户可以随时清除。

## 正式签名

正式签名私钥只能保存在维护者控制的安全位置或 GitHub Actions Secrets 中。发布任务必须校验包名、版本、签名方案和证书指纹。详见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。

## 不支持的用途

本项目不提供端口扫描、漏洞探测、压力测试、任意 hosts/路由修改、代理服务、凭据收集或访问控制绕过。

---

The Android scan uses a cached public maintained pool and a dynamic speed target, then requires route validation against a locally parsed VMess/VLESS WS+TLS node template. Full node links and UUIDs are discarded after parsing. DNS synchronization remains optional and explicit.
