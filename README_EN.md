# RR Edge Hunter Android · CF 优选IP

> Android edition · [Desktop edition](https://github.com/Xiaowu7z/RR-Edge-Hunter) · [RR-vps community](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** is a local Android Cloudflare ingress-IP selector. First paste an existing VMess/VLESS WebSocket-over-TLS Argo node that already works in V2rayNG. The app extracts only its port, SNI, Host, and WS Path locally and immediately discards the UUID. It then generates 100 candidates per round, performs three checks with 50-way concurrency, and gives the 10 lowest-RTT candidates up to five seconds of real download traffic.

After meeting the bandwidth target, a candidate must also pass strict TLS/SNI/Host and WebSocket `101` routing checks on the original node port. Only a candidate that passes both gates is displayed as a bare IPv4/IPv6 address. Replace only the node's `address/server`; keep every other field unchanged.

## Install

Current version: **1.0.0** (`com.xiaowu7z.cfipoptimizer`)

[Download the latest universal APK](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk). No CPU-architecture choice is required.

### Obtainium

[Add this app to Obtainium for automatic updates](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.xiaowu7z.cfipoptimizer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Edge-Hunter-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22CF%20%E4%BC%98%E9%80%89IP%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Afalse%2C%5C%22releaseDateAsVersion%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5ECF-IP-Optimizer%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Afalse%7D%22%7D). The official full-config link fixes the package ID, GitHub source, exact stable APK asset, release-date update detection, and disables prereleases and CPU filtering. This lets Obtainium notice a re-published stable Release even while the app version remains 1.0.0 as requested. Confirm once; no advanced choices are required. If Android does not hand the link to Obtainium, open Obtainium, tap **Add App**, and paste this repository address into **Source URL**:

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
| Node gate | Original port, TLS SNI, HTTP Host, and WS Path must all pass |
| Output | Only a route-verified IP; replace `address/server` only |

The UI exposes one understandable mode instead of asking users to choose among Balanced, Asia Hunt, and Maximum Bandwidth. A round that does not reach the target is followed by a new round until a result is found or the user stops the scan. Traffic therefore depends on real network results and is not presented with a misleading fixed upper bound.

## How it works

1. Fetch IPv4/IPv6 ranges, the current speed-test URL, and the POP-location table from `https://www.baipiao.eu.org/cloudflare/`; cache successful data for six hours.
2. Sample up to 100 ranges per round. IPv4 retains the first three octets and randomizes the last; IPv6 retains the first three hextets and randomizes the remaining five. Safe user-imported IPs can occupy part of the round.
3. Check every candidate three times with 50-way concurrency. Each attempt includes TCP, optional TLS, and a `Host: cloudflare.com` request; any failed attempt or missing `CF-RAY` rejects the candidate.
4. Sort by average TCP latency and retain the best 10.
5. Pin the dynamically supplied speed host to each candidate in latency order. TLS mode retains platform certificate, SNI, Host, and actual-peer checks; non-TLS mode uses port 80.
6. Download for at most five seconds per candidate. Peak kB/s is calculated only from complete one-second windows; a final partial window is ignored.
7. After reaching `target Mbps × 128 kB/s`, pin the candidate to the original node port/SNI/Host/Path and require strict TLS plus a valid WebSocket `101` upgrade.
8. Return the first candidate that passes both bandwidth and node-route gates; otherwise continue or start a fresh round. Copy and DNS synchronization are enabled only for that result.

The app never tests the VPS origin IP, but it does require an existing node template so that the selected address is proven on the same route V2rayNG will use.

## Custom candidate pools

The advanced panel supports long paste, IPv4/IPv6 endpoint notation, bounded CIDRs, TXT/CSV/TSV/JSON/Base64 files, and public HTTPS subscriptions. Imported candidates need not intersect the current speed-host DNS answers or belong to a published Cloudflare CIDR. Private, loopback, link-local, reserved, wrong-family, and malformed entries are rejected. An external public address remains unusable until it passes the same three checks and real-download gate.

Android's system document picker is used, so broad storage permission is not requested. The default maintained endpoints are the public interfaces used by [badafans/better-cloudflare-ip](https://github.com/badafans/better-cloudflare-ip). This project independently implements the publicly described and observable behavior; the upstream repository currently declares no open-source license, so its source code is neither copied nor bundled here.

## V2rayNG node-usability gate

Argo validation is now part of the main workflow. Paste a complete `vmess://` or `vless://` share link. WebSocket + TLS nodes on Cloudflare HTTPS ports `443/2053/2083/2087/2096/8443` are supported. The input is cleared after parsing; UUIDs and full links are not persisted or logged.

Every winner must preserve the original TLS certificate/SNI, HTTP Host, actual TCP peer, and WS Path, and receive a valid WebSocket `101` plus `Sec-WebSocket-Accept`. The output remains a bare IP and no other node field is changed.

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

See [SECURITY.md](SECURITY.md) and [NOTICE.md](NOTICE.md).

## Build

JDK 17 and Android SDK 34 are required:

```bash
./gradlew :logic-tests:check :app:lintDebug :app:assembleDebug
```

Release signing is injected outside the repository; see [RELEASE_SIGNING.md](RELEASE_SIGNING.md). The version remains **1.0.0**.

Cloudflare, Android, and other marks belong to their respective owners. This is an independent, unofficial project.
