# Snap-to-Print

Offline Android -> Windows photo transfer for event photo booths. The operator takes pictures with the phone's normal camera app; each new photo from the event appears in a Windows folder within about a second or two. No internet, router, cable or cloud is needed: the laptop joins the phone's own Wi-Fi hotspot.

> **Status: written but not yet built or run on hardware.** The code was produced in an environment with no Android SDK or .NET SDK, so nothing here has been compiled or tested on a device. See `docs/KNOWN_LIMITATIONS.md` and `docs/ACCEPTANCE_CHECKLIST.md`.

## Get the installable files (least manual work: GitHub builds them for you)
1. Create a free GitHub account and a new **private** repository.
2. Upload this whole folder's contents to it (web "Add file -> Upload files", or `git init && git add . && git commit -m init && git push`). Make sure the `.github` folder is included.
3. Open the repo's **Actions** tab. The `build` workflow starts automatically (about 5-10 minutes).
4. When it finishes, open the run and download the two artifacts at the bottom:
   - `SnapToPrint-Android-APK` -> `app-debug.apk`
   - `SnapToPrint-Windows-EXE` -> `SnapToPrint.exe` (self-contained, no .NET install needed)
5. If a job is red, open it, copy the error text and send it to Claude to fix. Test steps are marked non-blocking so a failing test never blocks the packages.

## Install
- **Phone:** copy `app-debug.apk`, open it, allow "install unknown apps". Debug-signed, so Play Protect may warn.
- **Laptop:** run `SnapToPrint.exe`. Windows SmartScreen will warn (unsigned): "More info" -> "Run anyway". No installer; put it anywhere.

## One-time setup
1. Phone: open Snap-to-Print, tap **Allow photo access** and choose **Allow all** (not "selected photos"). Allow notifications. Tap **BATTERY SETTINGS** and set the app to Unrestricted.
2. Phone: turn on the Wi-Fi hotspot. Laptop: join it ("No Internet" is normal).
3. Laptop app: type the pairing code from the phone, choose a destination folder (default `Pictures\SnapToPrint`), **Save**, then **Test connection**.

## Each event
1. Hotspot on, laptop joined, laptop app shows green **CONNECTED · READY FOR EVENT**.
2. Phone: optionally type an event name, tap **START EVENT**. Wait for **EVENT ACTIVE**.
3. Take photos with the normal Camera app (photos saved in `DCIM/Camera`). They arrive in `Pictures\SnapToPrint\Event_<date>_<name>\`.
4. Tap **FINISH EVENT**. If photos are still undelivered you can finish capture and keep sending, or keep the event open.

## Local build (optional)
- Android: JDK 17, Android SDK 34, Gradle 8.9: `cd android && gradle assembleDebug` -> `android/app/build/outputs/apk/debug/app-debug.apk`; tests: `gradle testDebugUnitTest`.
- Windows: .NET 8 SDK: `dotnet test windows/SnapToPrint.Core.Tests` and `dotnet publish windows/SnapToPrint/SnapToPrint.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish`.

## Versions / requirements
Android min API 30 (Android 11), target 34; Kotlin 1.9.24, AGP 8.5.2; only dependency NanoHTTPD 2.3.1. Windows 10/11 x64; .NET 8 (WPF); Microsoft.Data.Sqlite 8.0.10. Phone must support a Wi-Fi hotspot; laptop needs Wi-Fi.

## Layout
`android/` Kotlin app · `windows/SnapToPrint.Core` logic · `windows/SnapToPrint` WPF UI · `windows/SnapToPrint.Core.Tests` · `protocol/protocol-v1.md` · `docs/` · `tests/README.md` · `.github/workflows/build.yml`
