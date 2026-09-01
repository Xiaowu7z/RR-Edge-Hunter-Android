# Android 更新日志

## 1.0.0

- 删除 RR 自写候选池、RTT、CF-RAY、下载测速、速度计算、排名与换轮代码。
- 删除节点链接、VMess/VLESS、Xray、运营商模式和自定义 IP 池。
- 原样内置提供的参考 APK 中 `arm64-v8a/libgojni.so` 与 gomobile JNI 绑定。
- 首页只保留参考 App 的 IPv4/IPv6、TLS/非 TLS和期望带宽。
- 保留参考 App 的停止、数据更新、结果与历史能力。
- 唯一额外业务功能为用户从结果页手动发起的 Cloudflare DNS-only A/AAAA 预览、确认和回读验证；扫描结束不会自动写 DNS。
