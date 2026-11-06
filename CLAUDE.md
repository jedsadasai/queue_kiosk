# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is
A native Kotlin Android queue-ticket kiosk (`com.queuekiosk`) that reimplements the **fleQ_KIOSK** app against the same server API: login → pick a Bluetooth printer → tap "กดคิว" → `add-queue` → ticket rendered to a bitmap and printed. `docs/FLEQ_ANALYSIS.md` has the reverse-engineering notes for the original APK (`fleQ_KIOSK_Rajthanee_Release_1.9.apk`, kept in the root and git-ignored). UI text is Thai.

## Commands (Windows, PowerShell)
`JAVA_HOME` must point to JDK 17–25. Android Studio's bundled JBR works, as does `$HOME\.jdks\jbr-21.0.11` on this machine.
```powershell
$env:JAVA_HOME="$HOME\.jdks\jbr-21.0.11"
.\gradlew.bat testDebugUnitTest assembleDebug          # all JVM unit tests + APK (app/build/outputs/apk/debug/app-debug.apk)
.\gradlew.bat :app:testDebugUnitTest --tests "com.queuekiosk.data.ApiFlowTest"            # one class
.\gradlew.bat :app:testDebugUnitTest --tests "com.queuekiosk.data.WorkingHoursTest.*close*" # name pattern
node tools/mock-server/server.js   # dependency-free fake fleQ API on :8080 (env: PORT, MOCK_PASSWORD, TOKEN_TTL_SEC, ORG_NAME)
```
- There is no lint or formatter configured.
- There are no instrumented tests. All tests are plain JVM tests under `app/src/test`, so code that should be tested must stay free of Android classes. `GlassBlur` works on `IntArray`s for this reason, and `SessionStore` is an interface so tests use `FakeSession` instead of `KioskPrefs`.

## Build setup quirks
- **AGP 9.5 with built-in Kotlin.** There is no `kotlin-android` plugin, so don't add one. Dependency versions are in `gradle/libs.versions.toml`.
- **SDK levels:** `minSdk 29`, `compileSdk`/`targetSdk 37` (Android 10–17). The README's version table says what differs per API level:
  - Location for Bluetooth scans on 10–11.
  - `BLUETOOTH_SCAN`/`CONNECT` on 12+.
  - Edge-to-edge insets on 15+.
  - `ACCESS_LOCAL_NETWORK` on 17, requested before login.
- **`:printer-epson` is optional and proprietary.** `settings.gradle.kts` includes it only if `printer-epson/libs/ePOS2.jar` exists, and the SDK binaries are git-ignored.
  - `app` never references Epson classes directly. `PrinterRegistry` loads `com.queuekiosk.printer.epson.EpsonEpos2Driver` by reflection and skips it if the class or native library is missing.
  - Keep it that way, so the build works without the SDK.
- **`:printer-core`** holds only the `PrinterDriver` interface, shared by `app` (ESC/POS driver) and `printer-epson`.

## Architecture
- **Service locator:** `KioskApp` builds the singletons: `KioskPrefs`, `AuthRepository`, `QueueRepository`, `PrinterRegistry`, `PrinterManager` and `TicketRenderer`. Screens reach them via the `context.kiosk` extension. There is no DI framework.
- **Screens:** Activities + ViewModels + viewBinding in `ui/`.
  - Flow: `SplashActivity` → silent re-login with 5 s retry, or `LoginActivity` → `PrinterSetupActivity` → `KioskActivity`.
  - Long-pressing `kioskHeader` opens the staff menu. Long-pressing the splash logo opens login.
- **Network** (`data/`, `data/api/`):
  - Retrofit with form-encoded posts. `AuthInterceptor` adds `Authorization: <token_type> <token>` from `SessionStore`.
  - `QueueRepository.addQueue()` handles a 401 by calling `auth.reLogin()` and retrying **once**.
  - Credentials are stored in EncryptedSharedPreferences (`KioskPrefs`) so re-login needs no user.
  - Server-format quirks are deliberate, matching fleQ:
    - the response field is misspelled `worklfow_name`;
    - 3-digit numbers are padded to 4 (`padNumber`).
- **Working hours:** `WorkingHours.isOpen` is checked in `KioskViewModel.takeQueue()`. It is client-side only: the open hour is included and the close hour excluded.
- **Printing** (`printer/`):
  - `PrinterManager` serialises every operation through one `Mutex`, so jobs queue. A print reconnects and retries once on failure.
  - The selected driver id and device are saved in prefs.
  - `BluetoothEscPosDriver` uses a classic SPP socket. `EscPosEncoder` turns the bitmap into `GS v 0` raster data.
- **Tickets** (`ticket/`): `TicketRenderer` inflates `res/layout/view_ticket.xml` (576 dp wide, 1 dp = 1 printer dot on 80 mm paper), measures it at the paper width and draws it to a bitmap.
  - Every driver prints that bitmap, so ticket design changes are layout-only.
  - `ticket_logo.png` is pure black and white on purpose, because thermal printers dither grey.
- **Auto start:** `BootReceiver` launches the app on `BOOT_COMPLETED`. This needs the user to grant "Display over other apps"; see the README.
  - Its intent filter has priority 999 so it isn't queued behind every other app's boot receiver (a ~45 s gain on Samsung).
  - It passes `SplashActivity.EXTRA_FROM_BOOT`, which skips the splash delay.
  - The splash waits for a network (`data/NetworkWaiter`) before each re-login attempt.
  - Don't use direct boot (`LOCKED_BOOT_COMPLETED`): `KioskPrefs` is in credential-encrypted storage, and `openSecurePrefs` deletes the file when it can't open it.

## Liquid glass UI (`ui/glass/`)
- **Screen root:** every screen's root view is `GlassBackdropLayout`. It draws `res/drawable-nodpi/bg_hospital.jpg` with a top-anchored centerCrop (`GlassBlur.cropToFill`), so the logo printed in the image is never cut off.
- **Blur:** `GlassBackground` blurs the image **once** (¼ scale, box blur, saturation boost). Each `LiquidGlassButton`, `GlassCardLayout` and `GlassTextView` then draws its own window region of that blurred copy through `GlassPainter`.
  - There is no `RenderEffect`, so this works on API 29+.
  - Glass views redraw themselves on scroll and layout.
  - Outside a backdrop (e.g. in dialogs) they fall back to a flat frost.
- **Space for the image's logo:** `BackdropHeaderSpace` reserves the logo band (`GlassBackground.LOGO_BAND_BOTTOM`, a fraction of image height).
  - In the portrait kiosk layout, the spacer weights keep the button above the building in the bottom ~40% of the image.
- **Attributes:** `app:glassVariant` (`primary|clear|card|danger`) and `app:glassCornerRadius`. Tints are the `glass_*` entries in `res/values/colors.xml`.
- **Two layouts per screen:** `res/layout-land/` has landscape versions of all four activity layouts, with content in the right half because the logo fills the upper-left.
  - When editing a screen, change **both** `layout/` and `layout-land/` and keep identical view IDs; viewBinding and the activities depend on them.
