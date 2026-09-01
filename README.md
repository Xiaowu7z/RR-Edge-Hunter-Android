# RR Edge Hunter Android · CF 优选IP

> 无需节点、无需订阅，直接在 Android 手机上优选 Cloudflare IPv4/IPv6。
>
> [下载 Android 版](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk) · [查看 Windows 电脑版](https://github.com/Xiaowu7z/RR-Edge-Hunter)

本项目使用参考 App 的原生 Go 引擎完成候选生成、延迟检测、下载测速、速度计算、排名和换轮。用户不需要提供 VMess/VLESS 节点、订阅链接、UUID 或其他代理信息。

## 主要功能

- 支持 IPv4、IPv6；
- 支持非 TLS 80 与 TLS 443；
- 可自行设置期望带宽，未达标时由原版引擎继续换轮；
- 显示测速进度、最终 IP、实测带宽、最高速度、延迟、数据中心和耗时；
- 支持复制结果、查看本机历史和手动更新原版数据；
- 测试完成后，可由用户手动把本轮唯一 IP 写入 Cloudflare A/AAAA 灰云记录。

Android 版不会自动修改 DNS，也不会同时添加多个 IP。需要定时自动测试和每轮自动解析时，请使用 [Windows 电脑版](https://github.com/Xiaowu7z/RR-Edge-Hunter)。

## 下载与使用

当前版本：**1.0.0**，包名：`com.xiaowu7z.cfipoptimizer`。

[下载最新版 CF-IP-Optimizer.apk](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

APK 仅包含参考 App 提供的 `arm64-v8a` 原生引擎，适用于现代 64 位 Android 设备。

1. 下载并安装 APK；
2. 选择 IPv4/IPv6、TLS/非 TLS，并填写期望带宽；
3. 点击开始，等待原版引擎返回一个达标 IP；
4. 复制结果自行使用，或从结果页手动添加到 Cloudflare DNS。

## 默认值

| 项目 | 默认值 |
| --- | --- |
| IP 协议 | IPv4 |
| 连接方式 | 非 TLS 80 |
| 期望带宽 | 1 Mbps |

这些默认值与提供的参考 APK 一致。引擎未找到达标 IP 时会继续换轮；目标越高，耗时和流量越大，可随时停止。

## 测速原理与参考代码

Android 测速完整调用参考 APK 的原生 Go 引擎。RR 没有重新实现候选生成、RTT、CF-RAY、下载测速、速度计算、排名或换轮，也没有加入 Xray、VMess/VLESS、运营商模式或自定义 IP 池。

提交到仓库的以下部分来自参考 APK：

- 原生引擎：`app/src/main/jniLibs/arm64-v8a/libgojni.so`
- gomobile JNI 绑定：`app/src/go/` 与 `app/src/com/cf/ip/better/`

校验值：

| 文件 | SHA-256 |
| --- | --- |
| 提供的参考 APK | `5d700e43345dfb291887f8777a3d442e2829445d6f58b5c4e12abd08dfc9e68c` |
| APK 内 `libgojni.so` | `fbfea92ee11be855b5f8bbfe66bd37c5134a7472d19c0ab6699a82672df46c6a` |
| 本仓库 `libgojni.so` | `fbfea92ee11be855b5f8bbfe66bd37c5134a7472d19c0ab6699a82672df46c6a` |

CI 会校验原生库哈希，并在 APK 构建后再次解包验证；同时确认包内没有 Xray/libV2Ray。

界面只调用参考 APK 导出的原生接口：

- `Better.getIPs(useIPv4, useTLS, bandwidthMbps)`
- `Better.getProgress()`
- `Better.cancelScan()`
- `Better.updateData()`
- `Better.setCacheDir(path)`

最终 JSON 字段 `ip`、`bandwidth`、`realBandwidth`、`maxSpeed`、`latencyMs`、`dataCenter`、`elapsed` 也按参考 App 原格式读取。

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

不需要下载 `libXray.aar`。正式签名说明见 [RELEASE_SIGNING.md](RELEASE_SIGNING.md)。

详见 [安全说明](SECURITY.md)、[使用说明](NOTICE.md) 与 [第三方说明](THIRD_PARTY_NOTICES.md)。
