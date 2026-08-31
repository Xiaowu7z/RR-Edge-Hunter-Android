# Android 正式签名与发布

RR Edge Hunter Android（CF 优选IP）从 `1.0.0` 起使用一套新的正式签名。私钥、密钥库和密码绝不能保存到 Git 仓库、APK、Release 描述、日志或构建产物中。

## 发布身份

| 项目 | 固定值 |
| --- | --- |
| 应用包名 | `com.xiaowu7z.cfipoptimizer` |
| 首个版本 | `1.0.0` |
| 首个 `versionCode` | `1` |
| 证书主体 | `CN=RR Edge Hunter Android Release, O=RR Edge Hunter, C=CN` |
| SHA-256 指纹 | **待生成新正式证书后填写；不得沿用旧项目指纹** |

生成正式证书后，先用 `keytool -list -v -keystore ...` 记录 SHA-256 指纹，并把去掉冒号的 64 位十六进制值设为 GitHub Actions Secret `CFIP_ANDROID_CERT_SHA256`。该值不是私钥，但必须与正式 APK 的签名证书完全一致。

## 生成新的正式密钥

只在维护者控制的设备上生成，并把 `.p12` 放在**仓库之外**的受保护位置。以下命令会交互式要求设置新密码；将该密码和生成的文件路径保存到可信密码管理器，不要粘贴到聊天、Issue 或仓库：

```bash
keytool -genkeypair \
  -storetype PKCS12 \
  -keystore /private/path/rr-edge-hunter-android-release.p12 \
  -alias rr-edge-hunter \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=RR Edge Hunter Android Release, O=RR Edge Hunter, C=CN"

keytool -list -v \
  -keystore /private/path/rr-edge-hunter-android-release.p12 \
  -alias rr-edge-hunter
```

PKCS#12 通常使用同一把存储与私钥密码；GitHub Secrets 中的 `CFIP_ANDROID_STORE_PASSWORD` 与 `CFIP_ANDROID_KEY_PASSWORD` 因此应填写同一个新密码，除非你的密钥工具明确创建了不同的密码。

## 本地正式构建

正式构建从环境变量注入仓库外的签名材料：

```bash
CFIP_RELEASE_STORE_FILE=/private/path/rr-edge-hunter-android-release.p12 \
CFIP_RELEASE_KEY_ALIAS=rr-edge-hunter \
CFIP_RELEASE_STORE_PASSWORD='store-password' \
CFIP_RELEASE_KEY_PASSWORD='key-password' \
./gradlew :app:assembleRelease
```

未设置上述新 PKCS#12 材料时，`assembleRelease`、`bundleRelease` 和 `packageRelease` 会直接失败；debug 构建不读取签名秘密。

构建后，使用 Android SDK Build Tools 中的 `apksigner` 验证：

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

必须确认：

1. 包名为 `com.xiaowu7z.cfipoptimizer`。
2. `versionName` 与待发布标签 `v<versionName>` 一致，且 `versionCode` 大于此前正式版本。
3. v3 签名为 `true`。应用 `minSdk` 为 Android 10（29），正式 APK 只使用 v3 方案；v1/v2 不属于支持范围。
4. 输出的签名证书 SHA-256 digest（v3 构建中显示为 `V3.0 Signer`）与新证书的固定 SHA-256 指纹完全一致。

## GitHub Actions 配置

在仓库的 **Settings → Secrets and variables → Actions** 中配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `CFIP_ANDROID_KEYSTORE_BASE64` | 新正式 PKCS#12 `.p12` 密钥库的 Base64 内容 |
| Secret | `CFIP_ANDROID_KEY_ALIAS` | 新私钥别名 |
| Secret | `CFIP_ANDROID_STORE_PASSWORD` | 密钥库密码 |
| Secret | `CFIP_ANDROID_KEY_PASSWORD` | 私钥密码 |
| Secret | `CFIP_ANDROID_CERT_SHA256` | 新正式证书的 SHA-256；可带或不带冒号 |

普通 push 和 pull request 只构建 debug APK，不读取上述签名材料。维护者从 `main` 分支在 **Actions → CF 优选IP Android Release → Run workflow** 手动启动正式发布；工作流会在构建、签名验证全部通过后自动创建唯一的 `vX.Y.Z` 标签和 GitHub Release。这样失败的构建不会留下发布标签。

发布前还必须在 **Settings → Environments** 创建 `android-release` 环境：只把上述 Secrets 放入该环境，启用发布前人工审批，并只允许受保护的 `main` 分支触发。随后在仓库 Rules 中禁止对 `main` 和发布标签的强推或覆盖。这样拥有普通写入权限的人也不能直接拿到签名材料或替换正式版本。

## 发布硬性检查

1. 工作流只接受 `main` 分支；成功后自动创建等于 `v<versionName>` 的三段数字版本标签。
2. `applicationId` 必须为 `com.xiaowu7z.cfipoptimizer`。
3. `versionCode` 必须为正整数，且高于所有历史稳定标签中的版本号。
4. `CFIP_ANDROID_CERT_SHA256` 必须是已配置的新证书的 64 位 SHA-256 指纹，不能是空值或占位值。
5. APK 必须通过 v3 签名校验，并与该指纹匹配。
6. 每个稳定版 Release 只发布一个固定名称的 universal APK：`CF-IP-Optimizer.apk`，并附同名 SHA-256 文件。这让用户始终可通过 `releases/latest/download/CF-IP-Optimizer.apk` 一键下载最新版。
7. 已存在的标签或 Release 不得覆盖、替换或补传资产。
