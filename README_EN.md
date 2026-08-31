# RR Edge Hunter Android · CF 优选IP

> 📱 **Android edition** · 💻 [RR Edge Hunter for desktop](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps community](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** is the Android Argo-entry selector in the RR Edge Hunter family. Given an existing Argo hostname, it evaluates bounded candidates from current DNS, Cloudflare-published ranges, and an optional imported pool. The selected IP goes in the node's `address/server` field while the original hostname remains TLS SNI and HTTP Host.

> Results apply only to the current device, egress network, and test run. Test again after changing carrier, Wi-Fi, VPN, proxy, or network egress.

## Install

Current version: **1.0.0** (`com.xiaowu7z.cfipoptimizer`)

[⬇️ **Download and install the latest CF 优选IP (recommended)**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

This is one universal APK. Download it and follow Android's installation prompt—there is no CPU architecture, release, or advanced-setting choice. On the first install, allow your browser to install apps if Android asks.

### Testing channel

[🧪 **Manually download the current testing APK**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/download/testing/CF-IP-Optimizer-testing.apk)

The testing APK and stable release intentionally remain at version **1.0.0**, so automatic updaters will not see it as a newer version. Download and install it manually over the existing app. It uses the same release signature and does not replace the stable `latest` link above.

### Automatic updates (optional)

[🔄 **Already use Obtainium? Enable automatic updates**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.xiaowu7z.cfipoptimizer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Edge-Hunter-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22CF%20%E4%BC%98%E9%80%89IP%22%7D)

The link already contains the repository, app name, author, and package ID. Review **CF 优选IP** and confirm the import; no regular expressions, CPU architecture, prerelease, or ordering options are needed. Obtainium will then track stable releases.

<details>
<summary>Troubleshooting: install and verification</summary>

If the automatic-update link does not open, install the [latest Obtainium](https://github.com/ImranR98/Obtainium/releases/latest) and add this repository URL only:

```text
https://github.com/Xiaowu7z/RR-Edge-Hunter-Android
```

Every stable release includes `CF-IP-Optimizer.apk.sha256`; see [all Releases](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases) for manual download or verification.

</details>

## Features

- Carrier profiles: Auto, China Mobile, China Telecom, and China Unicom.
- Independent IPv4, IPv6, and dual-stack modes.
- Balanced and Asia-hunting strategies; reliability and stable throughput remain primary, while POP evidence such as HKG, NRT, SIN, ICN, and TPE is only a tie-breaker.
- Pre, Micro, and Full staged tests with repeat checks for success rate, latency, TTFB, transfer performance, and variance; failed samples count as zero in ranking.
- One-click mode combines current DNS seeds, bounded samples from official Cloudflare CIDRs, and optional pasted/file/HTTPS-subscription pools. A current-DNS-only diagnostic remains available.
- Each candidate must pass normal certificate, SNI/Host, remote-peer, and Argo trace checks; an optional WS Path requires a complete WebSocket 101 handshake.
- Copy either the IP or a complete node-field summary: address/server, port 443, SNI, Host, and Path.
- Up to 50 local history entries.

The app requests only Internet and network-state permissions. File import uses Android's system document picker and does not request broad storage access.

## Candidate and test boundary

This is not an Internet-wide scanner. Each run first snapshots a user-authorized Argo hostname to verify Cloudflare proxying and obtain trusted seeds. One-click mode can add a small deterministic sample from Cloudflare-published CIDRs and imported candidates, with a hard limit per address family.

- Imported candidates do not have to appear in the hostname's current DNS answers, but every target must be inside an official Cloudflare CIDR.
- The final pool is capped at 48 IPv4 and 24 IPv6 candidates; long imported lists are deterministically spread-sampled.
- IPv4 and IPv6 are validated and reported independently.
- The app temporarily fixes each candidate IP for the Argo hostname while retaining its SNI, Host, and platform TLS certificate verification.
- Because an Argo hostname normally lacks `speed.cloudflare.com`'s `/__down`, Argo compatibility is validated with the user's hostname while staged throughput uses the public Cloudflare speed host on the same candidate IP.

The app does not provide or perform arbitrary forced routing, `hosts` changes, DNS record writes of any kind, proxy configuration, port scanning, vulnerability testing, stress testing, or access-control bypass.

## Custom IP input

Open Add/manage IP pool to paste addresses, import a local file, or fetch an IP subscription. Entries are normalized and deduplicated, with valid, ignored, IPv4, IPv6, and CIDR counts shown to the user.

CIDR expansion, candidate count, file size, and subscription size are bounded. Subscriptions accept only public **HTTPS** targets on the default `443` port; every redirect is revalidated and each connection is pinned to a validated public IP.

Importing an address does not make it eligible. It must pass the official-range, family, Argo TLS/route, actual-peer, and bounded-pool checks.

## Build and verification

JDK 17 and Android SDK 34 are required:

```bash
./gradlew :logic-tests:check :app:lintDebug :app:assembleDebug
```

`logic-tests` is deterministic and does not depend on public-network state. Network and device checks should run separately in a controlled environment. Release signing is injected outside the repository; the new-certificate placeholder and verification process are documented in [RELEASE_SIGNING.md](RELEASE_SIGNING.md).

## Scope

Use this project only to assess official Cloudflare entry IPs for Argo nodes you own or are explicitly authorized to use and test. Follow applicable law, provider policies, and service terms.

Cloudflare, Android, and other marks belong to their respective owners. This is an independent, unofficial project and is not affiliated with, sponsored by, or endorsed by those providers. See [NOTICE.md](NOTICE.md).
