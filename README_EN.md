# RR Edge Hunter Android · CF IP Optimizer

> Find a usable Cloudflare IPv4/IPv6 directly on Android. No proxy node, subscription, UUID, or server deployment is required.
>
> [Download Android APK](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk) · [Windows desktop version](https://github.com/Xiaowu7z/RR-Edge-Hunter)

The app uses the native Go engine from the supplied reference APK for candidate generation, latency checks, download measurement, speed calculation, ranking, and retry rounds.

## Features

- IPv4 and IPv6;
- non-TLS port 80 and TLS port 443;
- configurable target bandwidth;
- progress, final IP, measured bandwidth, peak speed, latency, data center, elapsed time, and local history;
- manual data updates and result copying;
- an optional, user-initiated write of the single result to a Cloudflare DNS-only A/AAAA record.

Android never writes DNS automatically and never adds multiple IPs. Use the [Windows desktop version](https://github.com/Xiaowu7z/RR-Edge-Hunter) for scheduled runs and optional per-run DNS updates.

## Quick start

1. Download and install the APK.
2. Select IPv4/IPv6, TLS/non-TLS, and the target bandwidth.
3. Start the scan and wait for the original engine to return one qualifying IP.
4. Copy the result, or manually add it to Cloudflare DNS from the result page.

Defaults match the supplied APK: IPv4, non-TLS port 80, and 1 Mbps.

## Reference engine

The committed `libgojni.so` is byte-identical to the APK copy:

```text
fbfea92ee11be855b5f8bbfe66bd37c5134a7472d19c0ab6699a82672df46c6a
```

The gomobile JNI bindings are also taken from the supplied APK. CI verifies the native digest before and after packaging and rejects Xray/libV2Ray content. RR does not add carrier modes, custom IP pools, or a second scan algorithm.

## Build

```bash
./gradlew --no-daemon :logic-tests:check :app:lintDebug :app:assembleDebug
```

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [SECURITY.md](SECURITY.md).
