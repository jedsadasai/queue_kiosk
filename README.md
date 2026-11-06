# Queue Kiosk

An Android (Kotlin) queue-ticket kiosk. It provides the same features as **fleQ_KIOSK**:

- **Login** to a fleQ-style server, with silent re-login after restarts or token expiry.
- **Bluetooth printer** discovery and connection. Generic ESC/POS printers work out of the box, and Epson TM printers via the optional ePOS2 SDK.
- **Queue ticket printing:** tap "กดคิว" → `add-queue` → ticket rendered to a bitmap → printed → shown on screen.

How fleQ itself works, with file/line evidence: [docs/FLEQ_ANALYSIS.md](docs/FLEQ_ANALYSIS.md).

## Layout
```
app/                         the kiosk app (com.queuekiosk)
  data/                      KioskPrefs, AuthRepository, QueueRepository, WorkingHours
  data/api/                  Retrofit ApiService, DTOs, ApiClientFactory (+ AuthInterceptor)
  printer/                   PrinterRegistry, PrinterManager (connection + print-job queue)
  printer/escpos/            BluetoothEscPosDriver (SPP socket), EscPosEncoder (GS v 0 raster)
  ticket/                    TicketRenderer (layout -> bitmap at paper width), QrCode
  ui/                        Splash, Login, PrinterSetup, Kiosk screens
  ui/glass/                  hospital background + liquid glass buttons/cards (blur, painter, views)
printer-core/                PrinterDriver interface (shared with the Epson module)
printer-epson/               optional Epson ePOS2 driver (built only if you add the SDK, see its README)
tools/mock-server/           dependency-free Node mock of the fleQ API
docs/FLEQ_ANALYSIS.md        reverse-engineering notes + evidence
```

## Flow
```
Splash ──no token──▶ Login ──▶ (BT permissions) ──▶ Printer setup ──▶ Kiosk
   └──token──▶ re-login (retry every 5 s) ──▶ saved printer? Kiosk : Printer setup

Kiosk "กดคิว":
  working hours? ─▶ POST /api/v1/add-queue (401 → re-login → retry once)
  ─▶ TicketRenderer.render() ─▶ PrinterManager.print() (mutex queue, reconnect+retry once)
  ─▶ dialog: printed (auto-close 5 s) | not printed (Close button, "please photograph")
Long-press the green header = staff menu (change printer / log out).
```

## Server contract (same as fleQ)
| | Request | Response |
|---|---|---|
| Login | `POST {url}/api/v1/login`, form `username`, `password` | `{"status":"success","data":{"token_type":"Bearer","token":"…","workflow":{"id":7,"name":"…","branch_name":"…"}}}` |
| Add queue | `POST {url}/api/v1/add-queue`, form `workflow_id`, header `Authorization: <token_type> <token>` | `{"status":"success","data":{"number":"001","total":"3","building":"…","doctor":null,"url_qr":"…","worklfow_name":"…"}}` |

## Supported Android versions: 10 – 17
`minSdk 29` (Android 10) · `compileSdk`/`targetSdk 37` (Android 17).

| Android | API | What the app does differently |
|---|---|---|
| 10 – 11 | 29 – 30 | Bluetooth discovery needs the **Location** permission *and* Location switched on. The app warns if it's off; paired printers are listed either way |
| 12 – 16 | 31 – 36 | Asks for **Nearby devices** (BLUETOOTH_SCAN + BLUETOOTH_CONNECT) |
| 15+ | 35+ | Edge-to-edge is enforced, so every screen pads itself for the system bars, display cutout and keyboard |
| 16 – 17 | 36 – 37 | Orientation locks are ignored on tablets (sw ≥ 600dp), so layouts work in both orientations |
| **17** | **37** | A LAN server (`http://192.168.x.x`) needs the new **ACCESS_LOCAL_NETWORK** permission. It's in the same "Nearby devices" prompt, which is shown **before** login. If it's denied, the login error says so (otherwise the call just times out) |

Native code: Epson `libepos2.so` v2.39.0 is **16 KB page-aligned** (checked), as required on Android 15+ devices with 16 KB pages.

## Build
Toolchain: **AGP 9.5.0** (built-in Kotlin, no `kotlin-android` plugin) · **Gradle 9.8.2** (wrapper included) · runs on **JDK 17–25**,
so Android Studio's bundled JBR works. Needs the *Android SDK Platform 37* and *Build-Tools 36.0.0*.

1. Open the `queue_kiosk` folder in Android Studio and let it sync.
2. Unit tests: `gradlew testDebugUnitTest`
3. APK: `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

From a terminal on Windows:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # or any JDK 17+
.\gradlew.bat assembleDebug
```

## Try it end to end without the real server
```bash
node tools/mock-server/server.js            # prints http://<your-PC-IP>:8080
# options: PORT=8080 MOCK_PASSWORD=secret TOKEN_TTL_SEC=60 ORG_NAME="โรงพยาบาลตัวอย่าง"
```
1. On the tablet, log in with Host/Url `http://<PC-IP>:8080` and any username/password.
2. Pair the thermal printer in Android **Settings → Bluetooth** first. Most SPP printers use PIN `0000` or `1234`.
3. In Printer setup, pick the printer → **ทดสอบพิมพ์** → **เริ่มใช้งาน**.
4. Tap **กดคิว**. The ticket prints with numbers 0001, 0002…
5. Set `TOKEN_TTL_SEC=10` to watch the app get a 401, re-login, and retry without the patient noticing.

## Printers
- **Generic ESC/POS (default):** Classic Bluetooth SPP. Choose paper width 80 mm (576 dots) or 58 mm (384 dots).
  Printers without a cutter ignore the cut command.
- **Epson TM (optional):** drop the ePOS2 SDK into `printer-epson/` (see [printer-epson/README.md](printer-epson/README.md)).
  "Epson TM (ePOS2 SDK)" then appears as a driver choice. Epson TM printers also work with the ESC/POS driver.
  **Already installed in this checkout:** ePOS2 SDK v2.39.0 (`ePOS2.jar` + `libepos2.so` for arm64-v8a, armeabi-v7a, x86, x86_64).
  The jar's class signatures and constants were checked against `EpsonEpos2Driver`. Both folders are git-ignored.
  The default series is TM-M30. Change `series` in `EpsonEpos2Driver` for other models (e.g. `TM_M30III = 32`, `TM_T82 = 10`).
- To add another vendor, implement `PrinterDriver` (in `printer-core`) and register it in `PrinterRegistry`.

## Customise
- Ticket design: `app/src/main/res/layout/view_ticket.xml`, a copy of fleQ's Rajthanee ticket
  (logo + hospital name, number with the building as a superscript, OPD/ER history sheet, date | time, footer).
  It is 576 dp wide, with 1 dp = 1 dot on 80 mm paper.
  - Hospital name: `ticket_org_name` in `strings.xml`. History-sheet lines: `ticket_history_fields`
    (`"label|suffix"`, e.g. `BW|Kg`; an empty item is a blank dotted line).
  - Login options: "แสดงจำนวนคิวรอ" (waiting count), "แสดงใบซักประวัติ OPD/ER" (history sheet),
    "พิมพ์ QR Code บนบัตรคิว" (prints `url_qr`; off by default, since fleQ never printed it).
  - Fonts (`res/font/`): Noto Serif Thai SemiBold (SIL OFL 1.1) and Arimo Bold (Apache 2.0), the same as fleQ.
- Logo: `res/drawable-nodpi/logo_hospital.png` (colour: splash card and launcher icon via
  `drawable/ic_launcher.xml`) and `res/drawable-nodpi/ticket_logo.png` (pure black/white for the thermal
  printer, so it prints crisp instead of dithered grey).
- Background: `res/drawable-nodpi/bg_hospital.jpg`, drawn behind every screen by `GlassBackdropLayout`.
  It fills the screen like centerCrop but is anchored to the **top**, so the logo printed on it is never cut off.
  - Portrait: content starts below the image's logo (`BackdropHeaderSpace`), and the kiosk button stays
    above the building in the bottom ~40%.
  - Landscape (`res/layout-land/`): the logo fills the upper-left, so content goes in the right half.
  - To use another image, replace the file. If its logo band has a different height, update
    `GlassBackground.LOGO_BAND_BOTTOM` (fraction of the image height). Supply at least 1200×1800 px for tablets.
- Liquid glass (`ui/glass/`): `LiquidGlassButton` (a MaterialButton), `GlassCardLayout` and `GlassTextView`.
  - Each draws the **blurred background behind itself**, plus a tint, a top sheen, a bright rim and a soft shadow.
  - The blur is precomputed once from the static background, so it works on Android 10+ without RenderEffect.
    In a dialog there is no background, so a flat frost is used instead.
  - `app:glassVariant`: `primary` (green, buttons' default), `clear` (white), `card` (forms; cards' default) or `danger`.
  - Tints: `glass_*` in `res/values/colors.xml`.
- Text: `app/src/main/res/values/strings.xml` (Thai, mostly taken from fleQ's wording).
- Package name: `com.queuekiosk` (`namespace`/`applicationId` in `app/build.gradle.kts`).

## Auto start when the tablet is switched on
`BootReceiver` (fleQ `OnDeviceBootReceiver`) opens the app on `BOOT_COMPLETED`. The splash screen then
re-logs in by itself, retrying every 5 s until Wi-Fi and the server are up, and goes to the kiosk screen.
It is on by default and can be switched off with "เปิดแอปอัตโนมัติเมื่อเปิดเครื่อง" at login.

How it is kept fast:
- **Receiver priority 999.** Android hands `BOOT_COMPLETED` to apps one at a time, highest priority first.
  On a Samsung SM-T505 (Android 12) the kiosk at the default priority opened **64 s** after unlock;
  with 999 it opens in **16–18 s**. The rest is Android finishing earlier boot broadcasts, which no app can skip.
- **No splash delay** when opened by the receiver.
- **Waits for Wi-Fi instead of failing.** If the network isn't up yet, the splash shows
  "กำลังรอเชื่อมต่อเครือข่าย…" and signs in the moment it connects (measured: 0.7 s after Wi-Fi).
  Failed sign-ins are retried after 1, 2, 3 s, then every 5 s.

One-time setup on each tablet:
1. **Allow "Display over other apps"** (แสดงทับแอปอื่น). Android 10+ blocks apps from opening themselves
   from the background without it. The login screen shows a red line to tap while it is missing.
   Or via adb: `adb shell appops set com.queuekiosk SYSTEM_ALERT_WINDOW allow`.
2. **Screen lock: None**. Android sends `BOOT_COMPLETED` only after the device is unlocked.
3. Open the app once after installing. Android doesn't send boot events to an app that has never been opened.
4. Some brands (Xiaomi, Oppo, Vivo, Huawei…) also need **Autostart** allowed in their own security/battery app.
   On Samsung: Settings → Apps → Queue Kiosk → Battery → **Unrestricted**, and keep it out of "Sleeping apps".

To measure it: `adb reboot`, then compare in `adb logcat -b events -v monotonic` the `am_proc_start … com.queuekiosk`
and `wm_create_activity … KioskActivity` lines (the receiver also logs `BootReceiver: … received N ms after boot`).

## Not ported from fleQ
- The open/close alarms (`RajtheneeAlarmManager`). Opening hours are checked when the button is pressed,
  and the screen is kept on only during them.
  For a locked-down kiosk, use Android's Lock Task / a kiosk launcher.
