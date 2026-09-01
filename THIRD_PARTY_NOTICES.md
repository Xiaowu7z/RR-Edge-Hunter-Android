# 第三方组件说明

正式 APK 在构建时下载并校验固定版本的 [XTLS/libXray v26.7.28](https://github.com/XTLS/libXray/releases/tag/v26.7.28)，用于完整 VMess/VLESS 节点的本地 Xray 出站延迟测试。

- libXray：MIT License，Copyright (c) 2023-2025 XTLS；许可证文本随 APK 位于 `assets/third_party/libXray-MIT.txt`。
- Xray-core：Mozilla Public License 2.0；许可证文本随 APK 位于 `assets/third_party/Xray-core-MPL-2.0.txt`，对应源代码可在 [XTLS/Xray-core v26.7.28](https://github.com/XTLS/Xray-core/tree/v26.7.28) 获取。

本项目没有修改上述第三方组件。构建流程校验 Android 归档 SHA-256：`28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`。
