# RR Edge Hunter Android · CF IP Optimizer

> Find a usable Cloudflare IPv4/IPv6 directly on Android. No proxy node, subscription, UUID, or server deployment is required.
>
> [Download Android APK](https://github.com/Xiaowu7z/RR-Edge-Hunter-Android/releases/latest/download/CF-IP-Optimizer.apk) · [Windows desktop version](https://github.com/Xiaowu7z/RR-Edge-Hunter)

RR Edge Hunter Android performs candidate generation, latency checks, download measurement, speed calculation, ranking, and retry rounds directly on the phone's current network.

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
3. Start the scan and wait for one qualifying IP.
4. Copy the result, or manually add it to Cloudflare DNS from the result page.

Defaults are IPv4, non-TLS port 80, and 1 Mbps.

## Selection flow

1. Prepare candidates for the selected IP family and transport.
2. Run latency checks and download measurements.
3. Finish when one IP reaches the target bandwidth, or automatically start another round.
4. Show the IP, bandwidth, peak speed, latency, data center, and elapsed time.
5. Copy the result or manually add it to Cloudflare DNS.

Third-party source and license information is kept in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) instead of the user interface.

## Build

```bash
./gradlew --no-daemon :logic-tests:check :app:lintDebug :app:assembleDebug
```

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [SECURITY.md](SECURITY.md).
