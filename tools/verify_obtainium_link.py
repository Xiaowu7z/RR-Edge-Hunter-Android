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
EXPECTED_APP = {
    "id": EXPECTED_PACKAGE,
    "url": EXPECTED_REPOSITORY,
    "author": "Xiaowu7z",
    "name": "CF IP Optimizer",
}


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
    if app != EXPECTED_APP:
        raise SystemExit("Obtainium 必须使用不含高级参数的最小官方配置")
    if not app["name"].isascii():
        raise SystemExit("Obtainium 深链中的应用名称必须仅使用 ASCII 字符")

    print("Obtainium minimal one-tap link: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
