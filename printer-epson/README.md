# Optional Epson ePOS2 driver

This module is only part of the build when `printer-epson/libs/ePOS2.jar` exists (see `settings.gradle.kts`).
Without it, the app still builds and prints through the generic Bluetooth ESC/POS driver. Epson TM
printers also understand ESC/POS, so most of them work without this module.

## Enabling it
1. Download **Epson ePOS SDK for Android** from Epson's developer site. Use v2.20 or newer, which
   supports the Android 12+ Bluetooth permissions.
2. Copy the files:
   - `ePOS2.jar` → `printer-epson/libs/ePOS2.jar`
   - `libepos2.so` for each ABI → `printer-epson/src/main/jniLibs/<abi>/libepos2.so`
     (e.g. `arm64-v8a`, `armeabi-v7a`). fleQ shipped only `armeabi`, which modern 64-bit tablets can't load.
3. Sync Gradle. `PrinterRegistry` finds `com.queuekiosk.printer.epson.EpsonEpos2Driver` by reflection,
   and "Epson TM (ePOS2 SDK)" appears in the printer setup screen.

Both folders are git-ignored because the SDK must not be redistributed.

## What it does (mirrors fleQ_KIOSK)
| fleQ code | Here |
|---|---|
| `SetUpPrinterActivity.doDiscoveryDevice()` (`PORTTYPE_BLUETOOTH`) | `discover()` |
| `new Printer(1 /*TM_M30*/, 6 /*MODEL_SOUTHASIA*/, ctx)`, `connect(target, PARAM_DEFAULT)`, `beginTransaction()` | `connect()` |
| `addTextAlign`, `addImage(... COLOR_1, MODE_MONO_HIGH_DENSITY, HALFTONE_DITHER, PARAM_DEFAULT, COMPRESS_AUTO)`, `addFeedLine(1)`, `addCut(CUT_FEED)`, status check, `sendData(PARAM_DEFAULT)` | `print()`. It also waits for `onPtrReceive` and checks the result code |
| `endTransaction()`, `disconnect()`, `clearCommandBuffer()` | `disconnect()` |

To use a different TM series, change the default `series` in `EpsonEpos2Driver`
(e.g. `TM_T82 = 10`, `TM_T88 = 12`, values from the SDK's `Printer` class).
