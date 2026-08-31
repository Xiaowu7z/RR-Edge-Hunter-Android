#!/usr/bin/env python3
"""Validate the README's simple one-tap Obtainium link."""

from __future__ import annotations

from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
PREFIX = "obtainium://add/"
EXPECTED_REPOSITORY = "https://github.com/Xiaowu7z/RR-Edge-Hunter-Android"
EXPECTED_DEEP_LINK = PREFIX + EXPECTED_REPOSITORY


def main() -> int:
    markdown = README.read_text(encoding="utf-8")
    match = re.search(
        r"\[🔄 \*\*一键加入 Obtainium 自动更新\*\*\]\((https://apps\.obtainium\.imranr\.dev/redirect\?r=[^)]+)\)",
        markdown,
    )
    if match is None:
        raise SystemExit("README 缺少 Obtainium 一键添加链接")

    link = match.group(1)
    if "%" in link:
        raise SystemExit("Obtainium 简单深链不得携带百分号编码")

    redirect = urlsplit(link)
    values = parse_qs(redirect.query, strict_parsing=True)
    deep_link = values.get("r", [""])[0]
    if deep_link != EXPECTED_DEEP_LINK:
        raise SystemExit("Obtainium 跳转参数必须是仓库地址的简单 add 深链")

    source = deep_link.removeprefix(PREFIX)
    parsed_source = urlsplit(source)
    if parsed_source.scheme != "https" or parsed_source.netloc != "github.com":
        raise SystemExit("Obtainium 来源必须是公开 GitHub HTTPS 仓库")

    print("Obtainium simple one-tap link: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
