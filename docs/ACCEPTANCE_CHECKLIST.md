# Manual acceptance checklist (real phone + laptop)
Record phone model, Android version, Windows version, result, notes.
1. [ ] Connectivity gate: phone hotspot on, mobile data OFF, laptop joined. *Test connection* shows "PHONE APP REACHED", pairing accepted, authenticated round-trip OK. (Record the address found and ms.)
2. [ ] Phone with >=1000 existing camera photos: START EVENT -> none appear on laptop.
3. [ ] Take 20 photos: each appears exactly once, correct bytes (compare SHA-256 of one against the phone original).
4. [ ] Measure latency: shutter -> file visible (stopwatch/video of both screens, >=50 photos); report p95. Target <= 3 s.
5. [ ] Lock phone, open Camera, wait 5 min, take photos: still delivered.
6. [ ] Hotspot off, take 10 photos, hotspot on: all 10 arrive automatically, once each.
7. [ ] Force-kill laptop app mid-session, reopen: no loss/duplicates. Swipe-away phone app (not force-stop): service/queue recover.
8. [ ] Phone reboot mid-event, reopen app: queue intact, delivered.
9. [ ] Revoke photo permission: red "PHOTO PERMISSION OFF"; re-grant: resumes.
10. [ ] Fill laptop disk / make folder read-only: clear error; recover afterwards.
11. [ ] A photo taken just before START but saved after: not transferred.
12. [ ] Finish with pending photos: choose "finish, keep sending"; start a **new** event; old photos land in the OLD folder only.
13. [ ] 100-image session completes with exactly 100 files.
14. [ ] Screenshot and video taken during event: not transferred.
15. [ ] HEIC camera setting (if offered): files keep `.heic`.
