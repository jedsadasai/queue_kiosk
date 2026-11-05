# fleQ_KIOSK (Rajthanee v1.9): how login, Bluetooth printer and queue printing work

Source: `fleQ_KIOSK_Rajthanee_Release_1.9.apk`, decompiled to
`D:\zzzzzzz\zAPK\fleQ_KIOSK_Rajthanee_Release_1.9.apk_Decompiler.com\`.
Below, `SRC` = `sources\com\blueseas\kioskrajthanee\` and line numbers refer to those decompiled files.

- **Package:** `com.blueseas.kioskrajthanee`, versionName 1.9, compileSdk 29.
- **Stack:** Java, ButterKnife, Retrofit2 + Gson + OkHttp, Epson ePOS2 SDK (`resources/lib/armeabi/libepos2.so`), QRGen.
- **Screens** (AndroidManifest.xml): `SplashActivity` → `LoginActivity` → `SetUpPrinterActivity` → `MainActivity`.
- **Permissions:** BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_COARSE_LOCATION, INTERNET, WRITE_EXTERNAL_STORAGE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED.

```mermaid
sequenceDiagram
    participant S as SplashActivity
    participant L as LoginActivity
    participant P as SetUpPrinterActivity
    participant M as MainActivity
    participant API as fleQ server
    participant PR as Epson printer (BT)
    S->>S: token empty?
    alt no token
        S->>L: open
        L->>API: POST /api/v1/login (username, password)
        API-->>L: token_type, token, workflow.id
        L->>L: request permissions
    else has token
        S->>API: re-login with saved credentials (retry every 5 s)
    end
    S->>P: no saved printer
    S->>M: printer saved
    P->>PR: Discovery.start(PORTTYPE_BLUETOOTH)
    PR-->>P: DeviceInfo(name, "BT:xx:xx..")
    P->>M: device_info (saved as selected_printer)
    M->>PR: new Printer(TM_M30, MODEL_SOUTHASIA); connect(target); beginTransaction()
    Note over M: patient taps "กดคิว"
    M->>API: re-login (fresh token)
    M->>API: POST /api/v1/add-queue (workflow_id) + Authorization
    API-->>M: number, total, building, url_qr
    M->>M: inflate ticket layout -> bitmap (550 px)
    M->>PR: addImage + addFeedLine + addCut; getStatus(); sendData()
    M->>M: dialog with ticket (auto-close, or Close button if print failed)
```

## 1. Login
| Step | Evidence |
|---|---|
| App start: no token → Login; token exists → silent re-login | `SRC/activities/SplashActivity.java` (`getToken().isEmpty()` → `goToLoginActivity`, else `doReLogin`) |
| Form fields: URL, username, password, open/close hour, "show history sheet", "show total queue" | `SRC/activities/LoginActivity.java:40-68` |
| Validation: all non-empty, URL starts with `http`, `Patterns.WEB_URL`, both hours chosen | `LoginActivity.java:221-245` |
| Saves the fields to SharedPreferences **before** the call (password in plaintext) | `LoginActivity.java:247-259`, `SRC/utils/RajthaneeSharedPreferences.java` |
| API: `POST /api/v1/login`, form-encoded `username`, `password` | `SRC/apicall/APIInterface.java:15-17` |
| Response `{status:"success", data:{token_type, token, expire, workflow:{id,name,letter,branch_name,start}, branch, building, ...}}` | `SRC/pojo/LoginPojo.java` |
| On success: token = `token_type + " " + token`, saves `workflow.id`, sets `firstSetup=false` | `SRC/apicall/LoginCall.java:57-72` |
| Errors: 401 → "Username/Password อาจไม่ถูกต้อง"; 500 → "url อาจไม่ถูกต้อง"; retries every 5 s (except during first setup) | `LoginCall.java:42-50, 101-113` |
| After login: permissions (WRITE_EXTERNAL_STORAGE → ACCESS_COARSE_LOCATION). No saved printer → SetUpPrinter, else Main | `LoginActivity.java:277-306`, `SRC/utils/Utils.java#checkSelectedPrinter` |
| Authenticated calls: `Authorization: <token>` header | `SRC/apicall/APIClient.java:24-34` |
| **Re-login before every ticket** | `MainActivity.java:378-387` → `doReLogin()` → `SRC/apicall/ReLoginCall.java` → `startWorking()` → add-queue |

## 2. Connecting the Bluetooth printer (Epson ePOS2 SDK)
| Step | Evidence |
|---|---|
| Discovery filter: `setPortType(2)` = `PORTTYPE_BLUETOOTH`, `setDeviceType(0)` = TYPE_ALL, `setDeviceModel(0)` = MODEL_ALL, `setEpsonFilter(0)` = FILTER_NAME | `SRC/activities/SetUpPrinterActivity.java:69-82`; constants in `sources/com/epson/epos2/discovery/Discovery.java` |
| Each result → `DeviceItem(deviceInfo.getDeviceName(), deviceInfo.getTarget())`; target looks like `BT:00:11:22:..` | `SetUpPrinterActivity.java:42-51`, `SRC/item/DeviceItem.java` |
| Scans only if the BT adapter is enabled; BT on/off broadcast restarts or clears discovery | `SetUpPrinterActivity.java:94-131`, `SRC/receiver/OnBluetoothEnableReceiver.java` |
| Auto-select: a found target equal to the saved one jumps to Main (and toasts "not found" for every other device); tapping a row also goes to Main | `SRC/adapter/DeviceAdapter.java#onBindViewHolder` |
| Main stores the target as `selected_printer` | `MainActivity.java:107-109` |
| `onStart` (after a 1 s delay): `new Printer(1, 6, ctx)`, i.e. **TM_M30**, **MODEL_SOUTHASIA**, then `connect(target, -2 /*PARAM_DEFAULT*/)` and `beginTransaction()`. Connect error → back to SetUpPrinter | `MainActivity.java:179-256`; constants in `sources/com/epson/epos2/printer/Printer.java`, `CommonPrinter.java` |
| `onStop`: `endTransaction()` → `disconnect()` → `clearCommandBuffer()`, listener = null | `MainActivity.java:301-350` |

## 3. Printing a queue ticket
"Print queue" in fleQ means printing a **queue ticket**. There is no print-job queue. Overlapping jobs are
prevented only by disabling the button (`MainActivity.java:379, 409-411`).

| Step | Evidence |
|---|---|
| Tap "กดคิว" → outside working hours? toast. Otherwise re-login | `MainActivity.java:378-387`, `SRC/utils/RajtheneeAlarmManager.java#checkIfWorkingTime` |
| `POST /api/v1/add-queue`, form `workflow_id`, with the Authorization header | `APIInterface.java:11-13`, `SRC/apicall/AddQueueCall.java` |
| Response `data:{number, total, building, doctor, url_qr, vn, worklfow_name}`. Note the typo **worklfow_name** | `SRC/pojo/AddQueuePojo.java` |
| 3-digit number gets a leading `0`. A QR bitmap is generated from `url_qr` but **never added to the ticket** | `MainActivity.java:413-437` |
| Ticket = `res/layout/view_queue_card_for_c_building.xml` (logo, hospital name, number, building, optional history sheet, "จำนวนคิวรอ %s คิว", date, time, footer) → drawing-cache bitmap → scaled to **550 px** wide | `MainActivity.java:439-479`, `SRC/utils/QueueCardCBuilding.java` |
| Epson commands: `addTextFont(1)` FONT_B, `addTextAlign(1)` CENTER, `addImage(bmp, 0, 0, w, h, 1 COLOR_1, 2 MODE_MONO_HIGH_DENSITY, 0 HALFTONE_DITHER, -2.0 PARAM_DEFAULT, 2 COMPRESS_AUTO)`, `addFeedLine(1)`, `addCut(1)` CUT_FEED | `MainActivity.java:447-452` |
| Send: `getStatus()` → printable only if `connection != 0 && online != 0` → `sendData(PARAM_DEFAULT)`; otherwise `disconnect()` | `MainActivity.java:575-614` (jadx couldn't decompile `printData()`; the bytecode in the comment shows the logic) |
| OK → `DialogQueueCard` with countdown auto-dismiss. Failure → `DialogQueueCardWithButton` (Close button, "please photograph your ticket") | `MainActivity.java:481-560`, `SRC/utils/DialogQueueCard*.java` |
| `onPtrReceive` → `clearCommandBuffer()` | `MainActivity.java:352-362` |

## 4. Weaknesses found (fixed in this project)
| fleQ | Evidence | Here |
|---|---|---|
| Password and token in plaintext SharedPreferences | `RajthaneeSharedPreferences.setPassword` | `EncryptedSharedPreferences` (`data/KioskPrefs.kt`) |
| HTTP **BODY** logging, including the login password, in release | `APIClient.java:19` | Logging only in debug builds; Authorization redacted |
| The auth client captures the token when built → can send a stale token | `APIClient.java:26` | `AuthInterceptor` reads the token per request |
| Full re-login before every ticket | `MainActivity.java:386` | Re-login only on 401, then retry once (`QueueRepository`) |
| Credentials saved even when login fails | `LoginActivity.java:238` | Saved only after a successful login |
| "Printer not found" toast for every non-matching discovered device | `DeviceAdapter.onBindViewHolder` | No side effects in the adapter; background auto-reconnect instead |
| Only Epson TM printers; only `armeabi` native lib | `MainActivity.java:219`, `lib/armeabi` | Pluggable drivers: generic ESC/POS over SPP + optional Epson |
| `sendData()` returning = "printed" | `printData()` | ESC/POS: socket write errors; Epson: waits for the `onPtrReceive` result code |
| QR generated but never printed | `MainActivity.java:422-426` | QR printed when `url_qr` is present |
| Deprecated drawing cache + fixed 550 px scale | `MainActivity.java:470-479` | Rendered at the exact paper width (576/384 dots) via Canvas |
