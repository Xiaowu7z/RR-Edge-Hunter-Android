# RR Edge Hunter Android · CF 优选IP

> 📱 **Android edition** · 💻 [RR Edge Hunter for desktop](https://github.com/Xiaowu7z/RR-Edge-Hunter) · 💬 [RR-vps community](https://t.me/GMgP4NG7lncwZGE1)

[中文](README.md) · [English](README_EN.md)

**CF 优选IP** is the Android member of the RR Edge Hunter family. It evaluates Cloudflare IPs that are actually assigned by DNS for a host the user is authorized to test, on the current Android device and network.

> Results apply only to the current device, egress network, and test run. Test again after changing carrier, Wi-Fi, VPN, proxy, or network egress.

## Install

Current version: **1.0.0** (`com.xiaowu7z.cfipoptimizer`)

[⬇️ **Download and install the latest CF 优选IP (recommended)**](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk)

This is one universal APK. Download it and follow Android's installation prompt—there is no CPU architecture, release, or advanced-setting choice. On the first install, allow your browser to install apps if Android asks.

### Automatic updates (optional)

[🔄 **Already use Obtainium? Enable automatic updates**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Xiaowu7z/RR-Edge-Hunter-Android)

Confirm **CF 优选IP** after the link opens. No regular expressions, CPU architecture, prerelease, or ordering options are needed.

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
- Balanced and Asia-hunting strategies, using measured POP evidence such as HKG, NRT, SIN, ICN, and TPE.
- Pre, Micro, and Full staged tests with repeat checks for success rate, latency, TTFB, transfer performance, and variance; failed samples count as zero in ranking.
- Current-DNS candidates plus custom paste, file, or subscription intersection filters; IPv4, IPv6, CIDR, TXT, CSV, TSV, JSON, and Base64 are supported.
- Inspectable IPv4/IPv6, source, DNS snapshot, POP, and result evidence; copy support.
- Up to 50 local history entries.

The app requests only Internet and network-state permissions. File import uses Android's system document picker and does not request broad storage access.

## Candidate and test boundary

This is not an Internet-wide scanner. For each run, the app takes a current DNS snapshot of a host the user is authorized to test. The only eligible targets are Cloudflare IPs actually assigned by that DNS snapshot.

- Built-in candidates must satisfy both the current DNS snapshot and allowed Cloudflare ranges.
- Custom IPs, CIDRs, files, and subscriptions are intersected with the current DNS results. Addresses outside that intersection are rejected rather than force-connected.
- IPv4 and IPv6 are validated and reported independently.
- SNI, Host, and TLS certificate validation remain intact; the app never disguises an arbitrary address as an authorized host.

The app does not provide or perform arbitrary forced routing, `hosts` changes, DNS record writes of any kind, proxy configuration, port scanning, vulnerability testing, stress testing, or access-control bypass.

## Custom IP input

Use Import-list intersection to paste addresses, import a local file, or fetch an IP subscription. Entries are normalized and deduplicated, with valid, ignored, IPv4, IPv6, and CIDR counts shown to the user.

CIDR expansion, candidate count, file size, and subscription size are bounded. Subscriptions accept only public **HTTPS** targets on the default `443` port; every redirect is revalidated and each connection is pinned to a validated public IP.

Importing an address does not make it eligible. Every custom item must still intersect with the current DNS snapshot and pass the Cloudflare-range check.

## Build and verification

JDK 17 and Android SDK 34 are required:

```bash
./gradlew :logic-tests:check :app:lintDebug :app:assembleDebug
```

`logic-tests` is deterministic and does not depend on public-network state. Network and device checks should run separately in a controlled environment. Release signing is injected outside the repository; the new-certificate placeholder and verification process are documented in [RELEASE_SIGNING.md](RELEASE_SIGNING.md).

## Scope

Use this project only to assess Cloudflare IPs that are currently assigned by DNS for hosts and networks you own or are explicitly authorized to test. Follow applicable law, provider policies, and service terms.

Cloudflare, Android, and other marks belong to their respective owners. This is an independent, unofficial project and is not affiliated with, sponsored by, or endorsed by those providers. See [NOTICE.md](NOTICE.md).
