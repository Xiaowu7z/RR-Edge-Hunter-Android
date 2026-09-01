# RR Edge Hunter Android · CF 优选IP

> Android edition · [Desktop edition](https://github.com/Xiaowu7z/RR-Edge-Hunter) · [RR-vps community](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** is a local Android Cloudflare ingress-IP selector. First paste an existing VMess/VLESS WebSocket-over-TLS Argo node that already works in V2rayNG. The app keeps its complete configuration only in the current screen session. It then generates 100 candidates per round, performs three checks with 50-way concurrency, and gives the 10 lowest-RTT candidates up to five seconds of real download traffic.

After meeting the bandwidth target, the app changes only `address/server` in the full Xray outbound, preserves the protocol, UUID, port, TLS/SNI, WS Host/Path, and all other node fields, then requests V2rayNG's default delay URL, `https://www.gstatic.com/generate_204`, through that node. Only candidates that pass both the download and full-proxy gates are displayed.

## Install

Current version: **1.0.0** (`com.xiaowu7z.cfipoptimizer`)

[Download the latest arm64-v8a APK](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk). It targets modern 64-bit Android phones and avoids a very large multi-ABI package containing four Xray runtimes.

### Obtainium

[Add this app to Obtainium for automatic updates](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.xiaowu7z.cfipoptimizer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Edge-Hunter-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22CF%20%E4%BC%98%E9%80%89IP%22%7D). This uses the same minimal official deep-link structure as RR Edge Atlas Android: package ID, GitHub source, author, and app name only. The stable Release contains one APK, so no asset filters or advanced choices are needed. If Android does not hand the link to Obtainium, open Obtainium, tap **Add App**, and paste this repository address into **Source URL**:

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

Leave the other options at their defaults. The repository retains only the latest stable APK.

## One-click defaults

| Setting | Default |
| --- | --- |
| Address family | IPv4 |
| Target bandwidth | 100 Mbps |
| Scan flow | Fast selection: 100 IPs → three checks → 10 lowest RTT → first target hit |
| Transport | TLS 443 by default, with strict certificate validation; optional plain HTTP 80 |
| Speed target | Dynamically supplied by the maintained endpoint, with cached/official fallback |
| Candidate source | Public `baipiao.eu.org` maintained pool, optionally plus user-imported safe public IPs |
| Node gate | A complete VMess/VLESS Xray outbound must reach V2rayNG's default `generate_204` URL |
| Output | Only an IP that passes the V2rayNG-equivalent proxy-delay test; replace `address/server` only |

The UI exposes one understandable mode instead of asking users to choose among Balanced, Asia Hunt, and Maximum Bandwidth. A round that does not reach the target is followed by a new round until a result is found or the user stops the scan. Traffic therefore depends on real network results and is not presented with a misleading fixed upper bound.

## How it works

1. Fetch IPv4/IPv6 ranges, the current speed-test URL, and the POP-location table from `https://www.baipiao.eu.org/cloudflare/`; cache successful data for six hours.
2. Sample up to 100 ranges per round. IPv4 retains the first three octets and randomizes the last; IPv6 retains the first three hextets and randomizes the remaining five. Safe user-imported IPs can occupy part of the round.
3. Check every candidate three times with 50-way concurrency. Each attempt includes TCP, optional TLS, and a `Host: cloudflare.com` request; any failed attempt or missing `CF-RAY` rejects the candidate.
4. Sort by average TCP latency and retain the best 10.
5. Pin the dynamically supplied speed host to each candidate in latency order. TLS mode retains platform certificate, SNI, Host, and actual-peer checks; non-TLS mode uses port 80.
6. Download for at most five seconds per candidate. Peak kB/s is calculated only from complete one-second windows; a final partial window is ignored.
7. After reaching `target Mbps × 128 kB/s`, change only `address/server` in the complete Xray node configuration and request V2rayNG's default `generate_204` delay URL through the VMess/VLESS outbound.
8. Return the first candidate that passes both bandwidth and node-route gates; otherwise continue or start a fresh round. Copy and DNS synchronization are enabled only for that result.

The app never tests the VPS origin IP, but it does require an existing node so the candidate is proven with the same protocol, credentials, port, TLS, and transport settings V2rayNG will use.

## Custom candidate pools

The advanced panel supports long paste, IPv4/IPv6 endpoint notation, bounded CIDRs, TXT/CSV/TSV/JSON/Base64 files, and public HTTPS subscriptions. Imported candidates need not intersect the current speed-host DNS answers or belong to a published Cloudflare CIDR. Private, loopback, link-local, reserved, wrong-family, and malformed entries are rejected. An external public address remains unusable until it passes the same three checks and real-download gate.

Android's system document picker is used, so broad storage permission is not requested. The default maintained endpoints are the public interfaces used by [badafans/better-cloudflare-ip](https://github.com/badafans/better-cloudflare-ip). This project independently implements the publicly described and observable behavior; the upstream repository currently declares no open-source license, so its source code is neither copied nor bundled here.

## V2rayNG node-usability gate

Argo validation is now part of the main workflow. Paste a complete `vmess://` or `vless://` share link. WebSocket + TLS nodes on Cloudflare HTTPS ports `443/2053/2083/2087/2096/8443` are supported. The input is cleared after recognition; the credential-bearing full configuration remains only in current-screen memory and never enters preferences, history, logs, or exports.

The APK uses a pinned official XTLS/libXray runtime to replace only the candidate address, start the complete VMess/VLESS outbound, and request `https://www.gstatic.com/generate_204`. The private-cache configuration file is deleted immediately after each call. This verifies real proxy usability rather than only TCP, TLS, or WebSocket reachability; the output remains a bare IP.

## Optional Cloudflare DNS synchronization

The result screen can write a champion to one explicitly selected Cloudflare DNS record. This feature is off by default and normal scanning/copying needs no Cloudflare credentials.

- IPv4 maps to `A`; IPv6 maps to `AAAA`.
- The record is forced to **DNS-only** (gray cloud).
- A 32-character Zone ID and full record FQDN are required; the app does not guess the Zone.
- Only an API Token is accepted. The minimum permission is **Zone / DNS / Edit** for the selected Zone; Global API Keys are rejected.
- Step one generates a read-only preview. Step two requires explicit confirmation, followed by read-back verification.
- Existing CNAMEs, duplicate same-type records, and ambiguous states are rejected; the app never deletes, merges, or converts them automatically.
- Tokens are excluded from measurement logs, history, exports, and error text.

By default, Android keeps the token in current-session memory only. Persistent storage occurs only when the user explicitly enables local secure storage; the token is then encrypted with an Android Keystore AES-GCM key and can be cleared from the UI. Zone ID and record name may be retained as non-secret preferences.

DNS synchronization is an optional output and does not alter the Argo hostname or any node port, UUID, SNI, Host, or Path.

## Security and privacy

- The app requests only Internet and network-state permissions.
- The default pool is downloaded from a public maintained endpoint and cached locally, with official Cloudflare ranges as the offline fallback. Explicit imports accept only safe public literals and remain unusable until the same live checks pass.
- TLS mode keeps platform certificate, SNI, Host, and actual-peer validation enabled. Plain HTTP 80 requires an explicit user choice.
- HTTPS subscriptions enforce public-target, size, redirect, and DNS-rebinding checks.
- API tokens never enter logs, history, or exports; persistent storage is explicit and Keystore-backed.
- The app does not provide port scanning, vulnerability testing, stress testing, arbitrary hosts/route changes, proxy configuration, or access-control bypass.

See [SECURITY.md](SECURITY.md), [NOTICE.md](NOTICE.md), and [third-party notices](THIRD_PARTY_NOTICES.md).

## Build

JDK 17 and Android SDK 34 are required. Place the pinned `libXray.aar` under `app/libs/` before a local build; CI downloads v26.7.28 from the official release and verifies its SHA-256:

```bash
./gradlew :logic-tests:check :app:lintDebug :app:assembleDebug
```

Release signing is injected outside the repository; see [RELEASE_SIGNING.md](RELEASE_SIGNING.md). The version remains **1.0.0**.

Cloudflare, Android, and other marks belong to their respective owners. This is an independent, unofficial project.
