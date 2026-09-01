# 安全说明

## 测速边界

- 应用不需要或接收节点链接、UUID、订阅链接或代理凭据。
- RR Kotlin 界面不生成候选、不探测 IP、不测速和不排名；这些工作由提供的 `libgojni.so` 完成。
- 原生引擎会产生真实网络与下载流量，未达标时持续换轮；用户可随时停止。
- 应用只申请 Internet 权限，不修改 hosts、系统路由或系统代理。

## Cloudflare DNS

- 只允许从当前已完成结果页主动开启。
- 扫描完成不会自动发起 DNS 请求；每次只处理本轮唯一 IP 与用户指定的一条记录。
- IPv4 写 A、IPv6 写 AAAA，强制 DNS-only 与自动 TTL。
- 必须先只读预览，再明确确认，并在写入后回读。
- 同名 CNAME、NS、多条同类型记录或预览后状态变化会拒绝。
- Token 不进入测速日志、历史或错误文本；持久保存必须由用户主动选择，并由 Android Keystore 加密。

不要在公开 Issue、截图或日志中公开 Cloudflare API Token、Zone ID 与域名组合。
