# Test map (none have been executed yet; CI runs them on push)
| Required scenario | Where | Status |
|---|---|---|
| 1 pre-existing photo excluded | android `EligibilityTest.preExistingPhotoIsNeverEligible` | written |
| 2 post-start photo eligible | `photoAfterStartIsEligible` | written (delivery end-to-end: manual) |
| 3 taken before, saved after -> excluded | `takenBeforeStartButSavedLaterIsExcluded` | written |
| 4 missing capture time fallback | `missingCaptureTimeUsesFallbackAndRecordsUnknown` | written |
| 5 repeated notifications, no duplicates | DB `UNIQUE(session_id, media_id)` | **no automated test** |
| 6 duplicate content request | windows `DuplicateRequestCreatesNoSecondFile` | written |
| 7 partial file not complete | `TruncatedBodyIsRejected...`, `InterruptedTransfer...` | written |
| 8 checksum mismatch never acked | `ChecksumMismatchIsNeverAcknowledgedOrCommitted` | written |
| 9, 10 disconnected capture delivered later | manual checklist #6 | manual |
| 11 interrupted transfer restarts | `InterruptedTransferLeavesNothingThenRestartsCleanly` | written |
| 12 crash vs checkpoint | design: no checkpoint (docs/ARCHITECTURE.md) | **no automated test** |
| 13 crash between commit and ack | `CrashBetweenCommitAndAck...`, `OrphanFileWithoutReceipt...` | written |
| 14 disk full pauses | `InsufficientDiskSpacePauses...` (laptop side only) | written; phone side manual |
| 15 revoked permission | service tick sets error | **no automated test** |
| 16 old session never in new folder | `DifferentSessionsGoToDifferentFolders` (laptop) | written |
| 17 repeated start no duplicate session | `EventManager.start` guard | **no automated test** |
| 18 no videos/screenshots | Images table + `DCIM/Camera/` path filter | **no automated test**; manual #14 |
Not built: network fault-injection mode, instrumented Android tests.
