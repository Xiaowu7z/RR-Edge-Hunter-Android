# RR Edge Hunter Android · CF 优选IP

> Android edition · [Desktop edition](https://github.com/Xiaowu7z/RR-Edge-Hunter) · [RR-vps community](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** is a local Android Cloudflare ingress-IP selector. The default scan requires no user hostname: it first performs three direct TCP-connect RTT checks against bounded official-range candidates, then pins `speed.cloudflare.com` to shortlisted addresses on port `443` for strict TLS certificate, SNI, Host, actual-peer, `CF-RAY`, and roughly one-second real-download validation.

The output is a bare IPv4 or IPv6 address. Put it only in the VMess/VLESS node's `address` or `server` field. Keep the node's original port, UUID, protocol, TLS SNI, HTTP Host, and WebSocket Path unchanged.

## Install

Current version: **1.0.0** (`com.xiaowu7z.cfipoptimizer`)

[Download the latest universal APK](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk). No CPU-architecture choice is required.

### Obtainium

[Add this app to Obtainium for automatic updates](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Xiaowu7z/RR-Edge-Hunter-Android). This uses Obtainium's simple official `obtainium://add/<repository>` deep link, with no JSON, regular expression, or percent-encoded payload. Confirm Add; Obtainium will select the repository's only stable APK automatically. If Android does not hand the link to Obtainium, open Obtainium, tap **Add App**, and paste this repository address into **Source URL**:

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

Leave the other options at their defaults. The repository retains only the latest stable APK.

## One-click defaults

| Setting | Default |
| --- | --- |
| Address family | IPv4 |
| Target bandwidth | 100 Mbps |
| Default strategy | Asia Hunt |
| Available strategies | Balanced / Asia Hunt / Maximum Bandwidth |
| Measurement identity | `speed.cloudflare.com:443` |
| Candidate source | Official Cloudflare default pool, optionally plus any user-imported safe public IPs |
| Output | Replace node `address/server` only |

All strategies run three TCP-connect RTT checks for up to 100 candidates per family, rejecting a candidate if any round fails. Balanced and Asia Hunt pass the ten lowest-RTT candidates to strict downloads and stop only after one IP reaches the target twice. Maximum Bandwidth tests a diverse 20-address shortlist and confirms the fastest three. Every copyable result needs two successful strict download samples.

### Strategies

- **Balanced:** stop after the target is reached twice, balancing speed and traffic.
- **Asia Hunt:** the same confirmed early-stop behavior, with Asian POPs used only as same-tier tie-breakers.
- **Maximum Bandwidth:** test a low-RTT majority plus cross-latency quantiles for 20 shortlisted IPs, then select by confirmed two-sample real-download speed; this mode uses more traffic.

## How it works

1. Load current `speed.cloudflare.com` DNS seeds and a bounded per-run rotating sample from Cloudflare-published CIDRs.
2. Optionally add safe public IP literals as restricted candidates; private, loopback, link-local, and reserved addresses never enter probing.
3. Pin `speed.cloudflare.com:443` to each candidate while retaining platform certificate, SNI, Host, and actual TCP-peer validation.
4. Run three direct TCP-connect RTT checks for up to 100 candidates per family; any failed round rejects that candidate. Concurrency is capped at 32 on Wi-Fi and 16 on mobile networks.
5. Balanced/Asia modes retain the ten lowest-RTT candidates. Maximum Bandwidth uses a 20-address shortlist with a low-RTT majority plus cross-latency quantiles.
6. Only the shortlist enters strict TLS/SNI/peer/CF-RAY download validation. Redirects, undersized bodies and samples shorter than the guarded window are rejected.
7. Confirmation failures are backfilled from the next first-round result until the required number of twice-successful candidates is reached or the shortlist is exhausted.
8. Only candidates with at least two successful samples can be copied to a node or synchronized to DNS. Maximum Bandwidth selects by confirmed average real-download speed; the other modes prioritize reliable floor and stability.

The default workflow measures the current Android network to Cloudflare ingress. It needs neither a VPS origin IP nor an Argo hostname.

## Custom candidate pools

The advanced panel supports long paste, IPv4/IPv6 endpoint notation, bounded CIDRs, TXT/CSV/TSV/JSON/Base64 files, and public HTTPS subscriptions. Imported candidates need not intersect the speed hostname's current DNS answers or belong to a published Cloudflare CIDR. Private, loopback, link-local, reserved, wrong-family, and malformed entries are rejected. An external public address is only a restricted input: it becomes copyable or DNS-syncable only after two strict `speed.cloudflare.com:443` samples pass platform certificate, SNI, Host, actual TCP-peer, 2xx, valid `CF-RAY`, duration, and payload checks. Each family is capped at 100 candidates, imports at 60 per family, and concurrency and traffic are bounded.

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
- The one-click default pool is bounded to official Cloudflare ranges. Explicit imports accept only safe public literals, do not inherit a system HTTP proxy, and remain unusable until strict validation passes.
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
