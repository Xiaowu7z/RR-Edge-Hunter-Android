# Android 更新日志 / Changelog

## 1.0.0

- 建立 RR Edge Hunter Android 独立仓库，应用名为 **CF 优选IP**，包名为 `com.xiaowu7z.cfipoptimizer`。
- 默认流程改为免域名 Cloudflare IP 优选：固定 `speed.cloudflare.com:443`，从官方默认池及用户可选导入的安全公网候选执行严格 TLS、多阶段、多轮实测。
- 首页默认设为 IPv4、100 Mbps、亚洲狩猎；稳定速度与成功率优先，亚洲 POP 仅在同档成绩中加分。
- 结果输出裸 IP，只用于节点 `address/server`；节点端口、UUID、协议、SNI、Host 与 WS Path 保持不变。
- 增加高级可选 Argo 兼容复核，支持原域名、TLS 端口和严格 WebSocket 101 校验，但不改变默认免域名流程。
- 支持长复制、TXT/CSV/TSV/JSON/Base64、受控 CIDR、系统文件选择器和 HTTPS 订阅；导入 IP 无需命中当前 DNS 或预先属于官方 CIDR，但必须是安全公网地址，并在输出前通过严格 TLS/真实对端/CF-RAY 双样本复测。
- 快筛改为每候选三次 TCP connect RTT；移动网络自动降低并发，严格 TLS 下载校验只对入围候选执行。
- 最大带宽扩大为 20 个多样候选；达标复测、最快候选复测与高级 Argo 验证均支持失败自动补位。
- 真实下载窗口只接受 2xx、有效 `CF-RAY` 和足量/足时样本，并分别统计 Payload、完整传输与 Call 总速率。
- CF 官方 CIDR 样本支持每轮有界轮转；长粘贴解析移出主线程。
- 增加 Cloudflare DNS 可选同步：IPv4=A、IPv6=AAAA、强制 DNS-only；Zone ID 与完整 FQDN 必填，采用只读预览、二次确认和写后回读验证，拒绝 CNAME 和重复记录。
- Cloudflare Token 最小权限为指定 Zone 的 Zone/DNS/Edit；Token 不进入日志、历史或导出。
- Android 默认只在会话内存保存 Token；用户可显式选择 Android Keystore 加密保存并随时清除。
- 保留运营商标签、IPv4/IPv6/双栈、均衡/亚洲狩猎、本地历史、停止取消和网络变化作废保护。
- 版本号保持 **1.0.0**，测试包与正式包使用相同正式签名。

### English summary

- Hostname-free default scan via pinned `speed.cloudflare.com:443` with IPv4 / 100 Mbps / Asia Hunt defaults.
- Bare-IP output changes node `address/server` only; optional Argo verification is an advanced gate.
- Optional two-phase DNS-only A/AAAA synchronization with session-only tokens by default and explicit Android Keystore storage.
