# RR Edge Hunter Android · CF IP Optimizer

The Android scan now calls the native Go engine from the supplied reference APK. RR no longer implements candidate generation, RTT/CF-RAY checks, download measurement, speed calculation, ranking, or retry rounds, and no proxy node link is required.

RR keeps the UI and adds one user-initiated output: after a scan, the user may manually write the single reference-engine IP to a confirmed Cloudflare DNS-only A/AAAA record. Android never writes DNS automatically after a scan.

Defaults match the supplied APK: IPv4, non-TLS port 80, and 1 Mbps.

The committed `libgojni.so` is byte-identical to the APK copy:

```text
fbfea92ee11be855b5f8bbfe66bd37c5134a7472d19c0ab6699a82672df46c6a
```

The gomobile JNI bindings are also taken from the supplied APK. CI verifies the native digest before and after packaging and rejects Xray/libV2Ray content.

Build:

```bash
./gradlew --no-daemon :logic-tests:check :app:lintDebug :app:assembleDebug
```

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [SECURITY.md](SECURITY.md).
