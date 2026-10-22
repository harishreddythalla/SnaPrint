# Troubleshooting

**Laptop says PHONE NOT FOUND:** hotspot on? Laptop joined to it (not another Wi-Fi)? Phone app open (its notification visible)? Press *Test connection* and read the output. If no gateway is listed, type the phone's IP (shown on the phone under "Phone addresses", the one on the hotspot interface, often `ap0`/`wlan1`) into the optional IP field and Save. Some phones block client-to-phone traffic ("AP isolation"/data-saver): try turning Data Saver off.
**PAIRING CODE REJECTED:** re-type the code currently shown on the phone (a new code invalidates the old one).
**Photos missing:** they must be in `DCIM/Camera` and taken after START EVENT was confirmed. Photo access must be "Allow all". Check the phone's Failed count; *Retry failed photos*.
**Transfers delayed:** check phone battery settings = Unrestricted; keep hotspot on; check laptop is not asleep (disable sleep while plugged in).
**Phone shows "PHOTO PERMISSION OFF":** reopen the app and grant access; it resumes automatically.
**"STORAGE FULL" (phone) / "DISK FULL" (laptop):** free space; transfers resume automatically.
**"DESTINATION FOLDER UNAVAILABLE":** pick a writable folder in Setup.
**Android force-stop:** if you force-stop the app, monitoring stops until you open it again.
Logs: laptop app -> *Open log folder*.
