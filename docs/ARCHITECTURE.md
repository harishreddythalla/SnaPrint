# Architecture

## Topology
Phone hotspot (WPA2) <- laptop joins. Phone runs a foreground service hosting an authenticated HTTP server (port 8765). The laptop polls it (~every 0.7 s) so it never needs an inbound port. The laptop finds the phone from the **Wi-Fi adapter's gateway/DHCP-server address** (no hardcoded IP, no subnet scan); a manual IP is a diagnostic fallback. `UseProxy=false` and no internet checks, so "No Internet" is irrelevant.

## Event baseline (eligibility)
On START: record `startMs`, then `MediaStore.getGeneration()` and `getVersion()`, then store the ids of all camera images with `generation_added <= gen` (the baseline), all in one SQLite transaction, **before** the session exists as STARTING. A photo is eligible only if: not in baseline; (if the MediaStore version is unchanged) `GENERATION_ADDED > gen`; its `DATE_TAKEN`, when present and sane, is `>= startMs`. Missing/insane capture time -> eligible via the fallback and recorded as `UNKNOWN_FALLBACK`. Capture time is re-checked just before staging, so late-written metadata can still exclude a pre-event shot. If the MediaStore version changes (DB reset), generation is ignored and the baseline id set is used. Only `DCIM/Camera/` images that are not `IS_PENDING` are considered; videos and screenshots are never queried.

## Queue durability
Detection is a stateless, idempotent scan (every 500 ms and on ContentObserver notifications). Rows are inserted with `UNIQUE(session_id, media_id)`, so repeated notifications cannot duplicate. There is no separate "checkpoint" to advance past unqueued photos: the baseline generation is constant for the session, so a crash can only cause a harmless rescan. Readiness = size unchanged across two checks + successful full read; the file is copied to private storage while hashing (SHA-256), then marked QUEUED with a sequence number in one transaction. The original is only read.

## Delivery
Windows downloads to a temp file, verifies size and hash, flushes, atomically moves, writes a receipt, then acks (see protocol). Phone deletes its staged copy only after storing the ack. Failures nack with exponential backoff + jitter and never block other photos.

## Crash recovery
- Phone killed mid-scan/stage: rows or `.tmp` files are re-processed on restart; `.tmp` files are deleted at service start.
- Phone dies after rename but before QUEUED: the orphan `.bin` is overwritten when re-staged.
- Windows dies mid-download: `.part` never reaches the event folder; retry restarts from byte 0.
- Windows commits but dies before ack: file + (receipt or identical-hash file) found on retry -> re-ack, no duplicate.
- Phone gets ack but dies before deleting staged copy: removed by startup cleanup.
- Reboot / process death: the service restarts (START_STICKY) or when the operator reopens the app; sessions and queue are in SQLite.
