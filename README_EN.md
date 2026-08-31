# RR Edge Hunter Android · CF 优选IP

> Android edition · [Desktop edition](https://github.com/Xiaowu7z/RR-Edge-Hunter) · [RR-vps community](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** is a local Android Cloudflare ingress-IP selector. The default scan requires no user hostname: it pins `speed.cloudflare.com` to each candidate on port `443`, retains normal TLS certificate, SNI, Host, and actual-peer validation, and performs staged multi-round downloads.

The output is a bare IPv4 or IPv6 address. Put it only in the VMess/VLESS node's `address` or `server` field. Keep the node's original port, UUID, protocol, TLS SNI, HTTP Host, and WebSocket Path unchanged.

## Install

Current version: **1.0.0** (`com.xiaowu7z.cfipoptimizer`)

[Download the latest universal APK](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk). No CPU-architecture choice is required.

### Obtainium

[Add this app to Obtainium for automatic updates](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.xiaowu7z.cfipoptimizer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Edge-Hunter-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22CF%20%E4%BC%98%E9%80%89IP%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22%5ECF-IP-Optimizer%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22includePrereleases%5C%22%3Afalse%7D%22%2C%22overrideSource%22%3Anull%7D). The button already selects the stable release and the single universal APK. If Android does not hand the link to Obtainium, open Obtainium, tap **Add App**, and paste this repository address into **Source URL**:

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

Leave the other options at their defaults. The repository retains only the latest stable APK.

## One-click defaults

| Setting | Default |
| --- | --- |
| Address family | IPv4 |
| Target bandwidth | 100 Mbps |
| Strategy | Asia Hunt |
| Measurement identity | `speed.cloudflare.com:443` |
| Candidate source | Official Cloudflare pool, optionally plus imported official-range IPs |
| Output | Replace node `address/server` only |

Asia Hunt prioritizes reliability, round floor, minimum/average throughput, and variance. POP labels such as HKG, NRT, SIN, ICN, and TPE are tie-breakers only.

## How it works

1. Load current `speed.cloudflare.com` DNS seeds and a bounded deterministic sample from Cloudflare-published CIDRs.
2. Optionally add imported addresses that belong to official Cloudflare ranges.
3. Pin `speed.cloudflare.com:443` to each candidate while retaining platform certificate, SNI, Host, and actual TCP-peer validation.
4. Run Pre, Micro, and repeated Full downloads; failed Full rounds count as `0 Mbps`.
5. Rank by reliability, round floor, minimum/average throughput, variance, and TTFB.

The default workflow measures the current Android network to Cloudflare ingress. It needs neither a VPS origin IP nor an Argo hostname.

## Custom candidate pools

The advanced panel supports long paste, IPv4/IPv6 endpoint notation, bounded CIDRs, TXT/CSV/TSV/JSON/Base64 files, and public HTTPS subscriptions. Imported candidates do not need to intersect the speed hostname's current DNS answers, but every tested address must belong to an official Cloudflare CIDR. Private, reserved, non-Cloudflare, wrong-family, and malformed entries are rejected; candidates, concurrency, and traffic are bounded.

Android's system document picker is used, so broad storage permission is not requested. Unofficial third-party relays are not mixed into the default official pool.

## Optional advanced Argo compatibility check

Normal scanning needs no hostname. Enable the advanced Argo check only to validate candidates against your own node. Supply the original TLS SNI/HTTP Host hostname, original TLS port, and optionally the WebSocket Path.

Candidates must then pass certificate, SNI, Host, and actual-peer checks; a supplied Path must complete a standard WebSocket `101` upgrade. This is an additional gate only. The output remains a bare IP, and all other node fields stay unchanged.

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
- Candidate probes are bounded to official Cloudflare ranges and do not inherit a system HTTP proxy.
- TLS certificate, SNI, Host, and actual-peer validation remain enabled.
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
