# Known limitations and unverified items
**Nothing has been compiled or run yet.** First CI run may reveal compile errors; send them to Claude.
- Unverified on hardware: hotspot reachability (client isolation varies by phone), MediaStore behaviour/GENERATION_ADDED on your phone, background execution on your manufacturer's ROM, the 3 s latency target, 1000-photo baseline, 100-image run.
- Plain HTTP with a bearer pairing code (no TLS pinning). The pairing code is stored in app-private SharedPreferences (not Android Keystore). Laptop token uses Windows DPAPI.
- The server binds all interfaces; access is protected only by the pairing code.
- No automatic start after phone reboot: reopen the app (Android restricts background starts of this service type).
- Foreground service type `dataSync`; if you raise targetSdk to 35+, Android limits dataSync to ~6 h per 24 h, so keep targetSdk 34 or switch service type.
- Photo access must be "Allow all" on Android 14+; partial access cannot see new photos.
- Camera must save to `DCIM/Camera` (change via `source_path` setting in the DB; no UI yet). Cameras that never fill DATE_TAKEN use the documented fallback.
- Transfers restart from byte 0 after an interruption (no range resume).
- Scan runs every 500 ms; if the MediaStore version changes mid-event it rescans all camera rows each tick (fine for thousands of rows).
- Cancelled-start sessions still deliver any photos captured during the failed handshake (never discard).
- No "strict review" mode UI for ambiguous photos; REVIEW_REQUIRED state is not implemented.
- Phone notification has no action buttons; use the app to finish.
- Windows file names: `0001_HHmmss.ext` (capture time, local). Data retention: staged copies deleted after ack; DB rows kept.
- Debug-signed APK / unsigned EXE trigger install warnings.
- Automated Kotlin/DB-level scenarios (duplicate MediaStore notifications, crash checkpoints, revoked permission) have no automated test yet; see tests/README.md.
