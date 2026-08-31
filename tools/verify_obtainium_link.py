#!/usr/bin/env python3
"""Validate the README's one-tap Obtainium configuration."""

from __future__ import annotations

import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
PREFIX = "obtainium://app/"
EXPECTED_REPOSITORY = "https://github.com/Xiaowu7z/RR-Edge-Hunter-Android"
EXPECTED_PACKAGE = "com.xiaowu7z.cfipoptimizer"
EXPECTED_APK = "CF-IP-Optimizer.apk"


def main() -> int:
    markdown = README.read_text(encoding="utf-8")
    match = re.search(
        r"\[🔄 \*\*一键加入 Obtainium 自动更新\*\*\]\((https://apps\.obtainium\.imranr\.dev/redirect\?r=[^)]+)\)",
        markdown,
    )
    if match is None:
        raise SystemExit("README 缺少 Obtainium 一键添加链接")

    redirect = urlsplit(match.group(1))
    values = parse_qs(redirect.query, strict_parsing=True)
    deep_link = values.get("r", [""])[0]
    if not deep_link.startswith(PREFIX):
        raise SystemExit("Obtainium 跳转参数不是 obtainium://app 深链")

    app = json.loads(deep_link[len(PREFIX) :])
    settings = json.loads(app["additionalSettings"])
    if app.get("id") != EXPECTED_PACKAGE or app.get("url") != EXPECTED_REPOSITORY:
        raise SystemExit("Obtainium 应用 ID 或仓库地址错误")
    if app.get("preferredApkIndex") != 0 or app.get("overrideSource") is not None:
        raise SystemExit("Obtainium 默认 APK 或来源设置错误")
    if settings.get("includePrereleases") is not False:
        raise SystemExit("Obtainium 必须只跟踪正式 Release")
    if settings.get("invertAPKFilter") is not False:
        raise SystemExit("Obtainium APK 过滤方向错误")

    apk_filter = re.compile(settings["apkFilterRegEx"])
    if apk_filter.fullmatch(EXPECTED_APK) is None:
        raise SystemExit("Obtainium 过滤器没有选中正式 APK")
    if apk_filter.fullmatch(f"{EXPECTED_APK}.sha256") is not None:
        raise SystemExit("Obtainium 过滤器错误选中了校验文件")

    print("Obtainium one-tap link: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
