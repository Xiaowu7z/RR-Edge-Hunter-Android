#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts | head -n 1)"
if [ -z "$VERSION_NAME" ]; then
  echo "无法从 app/build.gradle.kts 读取 versionName" >&2
  exit 2
fi

if [ -n "${CFIP_RELEASE_STORE_FILE:-}" ]; then
  : "${CFIP_RELEASE_KEY_ALIAS:?正式构建必须设置 CFIP_RELEASE_KEY_ALIAS}"
  : "${CFIP_RELEASE_STORE_PASSWORD:?正式构建必须设置 CFIP_RELEASE_STORE_PASSWORD}"
  : "${CFIP_RELEASE_KEY_PASSWORD:?正式构建必须设置 CFIP_RELEASE_KEY_PASSWORD}"
  ./gradlew --no-daemon :logic-tests:check :app:lintRelease :app:assembleRelease
  SOURCE_APK="app/build/outputs/apk/release/app-release.apk"
  OUTPUT_APK="${APK_OUTPUT:-$PROJECT_DIR/CF-IP-Optimizer-$VERSION_NAME.apk}"
else
  ./gradlew --no-daemon :logic-tests:check :app:lintDebug :app:assembleDebug
  SOURCE_APK="app/build/outputs/apk/debug/app-debug.apk"
  OUTPUT_APK="${APK_OUTPUT:-$PROJECT_DIR/CF-IP-Optimizer-$VERSION_NAME-debug.apk}"
fi

install -m 0644 "$SOURCE_APK" "$OUTPUT_APK"
sha256sum "$OUTPUT_APK"
printf '构建完成：%s\n' "$OUTPUT_APK"
