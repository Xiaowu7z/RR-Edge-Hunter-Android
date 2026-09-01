#!/usr/bin/env python3
"""Lock the Android shell to one original-engine result and manual-only DNS."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app" / "src" / "com" / "xiaowu7z" / "cfipoptimizer" / "MainActivity.kt"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> int:
    source = SOURCE.read_text(encoding="utf-8")
    require(source.count("Better.getIPs(") == 1, "Android 必须且只能从一个入口调用参考 App 扫描")
    require(
        source.count("showDnsSyncDialog(result.ip)") == 1,
        "Cloudflare DNS 必须且只能由结果页按钮发起",
    )
    require(
        'primaryButton("手动添加到 Cloudflare DNS") {\n            showDnsSyncDialog(result.ip)' in source,
        "结果页缺少用户手动添加解析入口",
    )
    finish_scan = source.index("    private fun finishScan(")
    finish_task = source.index("    private fun finishTask(", finish_scan)
    automatic_path = source[finish_scan:finish_task]
    require("showDnsSyncDialog" not in automatic_path, "扫描完成路径不得自动打开 DNS")
    require("CloudflareDns." not in automatic_path, "扫描完成路径不得自动调用 Cloudflare DNS")
    require("自动解析到 Cloudflare" not in source, "Android 文案不得把手动解析标成自动解析")
    print("Android reference/DNS contract: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
