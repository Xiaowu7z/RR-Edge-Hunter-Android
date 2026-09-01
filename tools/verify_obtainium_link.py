#!/usr/bin/env python3
"""Validate the README's official full-config Obtainium link."""

from __future__ import annotations

from pathlib import Path
import re
import json
from urllib.parse import parse_qs, unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
PREFIX = "obtainium://app/"
EXPECTED_REPOSITORY = "https://github.com/Xiaowu7z/RR-Edge-Hunter-Android"
EXPECTED_CONFIG = {
    "id": "com.xiaowu7z.cfipoptimizer",
    "url": EXPECTED_REPOSITORY,
    "author": "Xiaowu7z",
    "name": "CF 优选IP",
}


def main() -> int:
    markdown = README.read_text(encoding="utf-8")
    match = re.search(
        r"\[🔄 \*\*一键加入 Obtainium 自动更新\*\*\]\((https://apps\.obtainium\.imranr\.dev/redirect\?r=[^)]+)\)",
        markdown,
    )
    if match is None:
        raise SystemExit("README 缺少 Obtainium 一键添加链接")

    link = match.group(1)
    redirect = urlsplit(link)
    if redirect.scheme != "https" or redirect.netloc != "apps.obtainium.imranr.dev":
        raise SystemExit("Obtainium 必须使用官方 HTTPS 跳转页")
    values = parse_qs(redirect.query, strict_parsing=True, keep_blank_values=True)
    deep_link = values.get("r", [""])[0]
    if not deep_link.startswith(PREFIX):
        raise SystemExit("Obtainium 跳转参数必须是官方 app 完整配置深链")

    try:
        config = json.loads(unquote(deep_link.removeprefix(PREFIX)))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise SystemExit(f"Obtainium 配置不是合法 URL 编码 JSON：{exc}") from exc
    if config != EXPECTED_CONFIG:
        raise SystemExit("Obtainium 完整配置与正式应用身份不一致")

    parsed_source = urlsplit(config["url"])
    if parsed_source.scheme != "https" or parsed_source.netloc != "github.com":
        raise SystemExit("Obtainium 来源必须是公开 GitHub HTTPS 仓库")

    print("Obtainium full-config one-tap link: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
