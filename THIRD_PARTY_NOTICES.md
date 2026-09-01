# 第三方组件说明

## 提供的参考 APK 原生引擎

本项目按用户要求内置提供 APK 中未修改的 `arm64-v8a/libgojni.so`，并使用该 APK 的 gomobile JNI 绑定接口。

- 参考 APK SHA-256：`5d700e43345dfb291887f8777a3d442e2829445d6f58b5c4e12abd08dfc9e68c`
- `libgojni.so` SHA-256：`fbfea92ee11be855b5f8bbfe66bd37c5134a7472d19c0ab6699a82672df46c6a`

该二进制内可观察到 `better-cloudflare-ip` 的候选、RTT、CF-RAY、下载测速与数据接口流程。构建不对其原生代码进行修改。

提供的 APK 未附带可识别的许可证文件。分发者应自行确认其取得的使用与再分发授权；本说明不授予额外权利。
