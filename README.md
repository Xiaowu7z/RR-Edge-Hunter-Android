# RR Edge Hunter Android · CF 优选IP

> 无需节点、无需订阅，直接在 Android 手机上优选 Cloudflare IPv4/IPv6。
>
> [下载 Android 版](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk) · [查看 Windows 电脑版](https://github.com/Xiaowu7z/RR-Edge-Hunter)

RR Edge Hunter Android 会直接在手机当前网络上完成候选生成、延迟检测、下载测速、速度计算、排名和换轮。用户不需要提供 VMess/VLESS 节点、订阅链接、UUID 或其他代理信息。

## 主要功能

- 支持 IPv4、IPv6；
- 支持非 TLS 80 与 TLS 443；
- 可自行设置期望带宽，未达标时自动继续换轮；
- 显示测速进度、最终 IP、实测带宽、最高速度、延迟、数据中心和耗时；
- 支持复制结果、查看本机历史和手动更新 IP 池；
- 测试完成后，可由用户手动把本轮唯一 IP 写入 Cloudflare A/AAAA 灰云记录。

Android 版不会自动修改 DNS，也不会同时添加多个 IP。需要定时自动测试和每轮自动解析时，请使用 [Windows 电脑版](https://github.com/Xiaowu7z/RR-Edge-Hunter)。

## 下载与使用

当前版本：**1.0.0**，包名：`com.xiaowu7z.cfipoptimizer`。

[下载最新版 CF-IP-Optimizer.apk](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

APK 适用于现代 64 位 Android 设备（`arm64-v8a`）。

1. 下载并安装 APK；
2. 选择 IPv4/IPv6、TLS/非 TLS，并填写期望带宽；
3. 点击开始，等待程序返回一个达标 IP；
4. 复制结果自行使用，或从结果页手动添加到 Cloudflare DNS。

## 默认值

| 项目 | 默认值 |
| --- | --- |
| IP 协议 | IPv4 |
| 连接方式 | 非 TLS 80 |
| 期望带宽 | 1 Mbps |

未找到达标 IP 时会继续换轮；目标越高，耗时和流量越大，可随时停止。

## IP 优选流程

1. 根据 IPv4 / IPv6 和 TLS 选项准备候选池；
2. 完成延迟检测和下载测速；
3. 找到达到期望带宽的 IP 后结束本轮，否则自动换一批继续；
4. 在结果页显示 IP、实测带宽、最高速度、延迟、数据中心和耗时；
5. 用户复制结果自行使用，或手动添加到 Cloudflare DNS。

第三方代码与许可信息统一收录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，不占用普通用户的操作界面。

## 手动添加到 Cloudflare DNS

结果页提供“手动添加到 Cloudflare DNS”按钮。只有用户点击、查看预览并再次确认后，才会把本轮唯一 IP 写入指定域名；普通测速、查看结果和复制 IP 都不需要 Cloudflare 凭据。

- IPv4 写 A，IPv6 写 AAAA；
- 强制 DNS-only（灰云）与自动 TTL；
- 填写 Zone ID、完整域名和 API Token；
- 第一步只读预览，第二步明确确认；
- 扫描完成不会自动触发 DNS 请求，也不支持多个 IP 同时解析；
- 写入后回读验证；
- 同名 CNAME、NS 或多条同类型记录会拒绝；
- Token 默认只存在当前会话；只有用户主动勾选时才用 Android Keystore 加密保存。

## 构建

需要 JDK 17 和 Android SDK：

```bash
./gradlew --no-daemon :logic-tests:check :app:lintDebug :app:assembleDebug
```

正式签名说明见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。

详见 [安全说明](SECURITY.md)、[使用说明](NOTICE.md) 与 [第三方说明](THIRD_PARTY_NOTICES.md)。
