# Snap-to-Print protocol v1

Transport: HTTP/1.1 over the phone's Wi-Fi hotspot. The **phone hosts** (port **8765**); the **laptop initiates** every request.
Bodies are JSON (UTF-8, camelCase) except photo content (raw bytes).

## Authentication and pairing
- The phone shows a 20-character pairing code (100 bits, alphabet `A-Z` without I/O plus `2-9`, shown as `XXXX-XXXX-...`). The operator types it into the Windows app once.
- Every request except `GET /health` carries `Authorization: Bearer <code without dashes>`. Missing/wrong code -> `401 {"error":"unauthorized"}`.
- `GET /health` is unauthenticated and reveals only `{"app":"snap-to-print","protocol":1,"authorized":bool}` (used for discovery).
- **v1 limitation:** plain HTTP (no TLS pinning). Traffic stays on the WPA-protected hotspot. See docs/KNOWN_LIMITATIONS.md.

## Common
- Errors: `{"error":"<code>"}` with HTTP status. 404 `not_found`/`no_such_*`, 409 conflict, 410 gone, 500 `internal`.
- Timeouts (client): connect 3 s, request 30 s, health probe 1.5 s. Server socket read timeout 5 s.
- All endpoints are safe to repeat. Retries by the client use bounded backoff (1 s .. 10 s).

## Endpoints
| Method + path | Purpose | Response |
|---|---|---|
| `GET /api/v1/health` | liveness/discovery | `{"app","protocol":1,"authorized"}` |
| `GET /api/v1/session/current` | live session, else latest | `{"state","sessionId","label","startMs","readyAck"}` or `{"state":"NONE"}` |
| `POST /api/v1/session/{id}/ready` body `{"folder":"..."}` | laptop confirms folder is writable; phone flips STARTING -> ACTIVE (durably, before replying) | `{"state"}`; 404 unknown; 409 `cancelled`. Idempotent. |
| `GET /api/v1/queue/next` | next deliverable photo (oldest session first, then seq) | `{"item":{...}\|null,"pending":N,"failed":M}` |
| `GET /api/v1/photos/{id}/content` | stream staged original; header `X-Sha256` | bytes; 410 `already_acknowledged`/`staged_copy_missing`; 409 `not_ready` |
| `POST /api/v1/photos/{id}/ack` body `{"sha256","size"}` | laptop has verified + committed | `{"ok":true}`; 409 `checksum_mismatch` (never acks wrong data). Repeating an ack returns `{"ok":true,"duplicate":true}`. |
| `POST /api/v1/photos/{id}/nack` body `{"reason","retry":bool}` | receipt failed | `{"ok":true}`; retry -> RETRY_PENDING with backoff, else FAILED_PERMANENTLY. 8 attempts max. |
| `POST /api/v1/session/{id}/finish-ack` | laptop saw FINISHED and nothing pending | `{"ok":true}` (informational) |

### Queue item
```json
{"photoId":"6b0c...","sessionId":"f1d2...","sessionLabel":"Wedding","sessionStartMs":1760000000000,
 "seq":7,"fileName":"20261022_140002.jpg","mime":"image/jpeg","size":2483921,
 "capturedMs":1760000002000,"captureSource":"DATE_TAKEN","sha256":"ab12..."}
```
`captureSource` is `DATE_TAKEN` (trusted metadata) or `UNKNOWN_FALLBACK` (no trustworthy capture time; accepted on baseline/generation evidence).
`/queue/next` is a pure read: it does not change state, so it is safe to call repeatedly and after the laptop loses its local state. Items for **older sessions** carry their own `sessionId`/`sessionStartMs`/`sessionLabel`, so they are filed in their own folder, never the current one.

## Phone-side photo states
`DISCOVERED -> WAITING_FOR_FILE -> QUEUED -> ACKNOWLEDGED`, with `QUEUED -> RETRY_PENDING -> QUEUED/ACKNOWLEDGED` after a nack, `-> FAILED_PERMANENTLY` after exhausted retries (or a vanished source), and `EXCLUDED` for photos that fail eligibility (never sent).
A staged copy exists from QUEUED until ACKNOWLEDGED; it is deleted only after the acknowledgement is stored.

## Session states
`STARTING -> ACTIVE -> FINISHING -> FINISHED | FINISHED_WITH_PENDING -> FINISHED`, plus `CANCELLED` (only from STARTING).
"Connected/disconnected" is derived at display time from the last authenticated request (< 8 s), not stored.

## Receive semantics (laptop)
1. Download to `<root>\.incoming\<photoId>.part`, hashing as it streams.
2. Verify byte count and SHA-256; flush to disk.
3. `File.Move` (no overwrite) into the event folder; write the receipt row (SQLite, synchronous=FULL).
4. Only then `POST .../ack` with the *computed* sha256 and size.
If the ack is lost, the phone re-offers the item; the laptop finds the receipt (or an identical file) and just re-acks.
