# Loaded reflectively by com.queuekiosk.printer.PrinterRegistry
-keep class com.queuekiosk.printer.epson.EpsonEpos2Driver { public <init>(android.content.Context); }
# Epson SDK uses JNI callbacks into these classes
-keep class com.epson.** { *; }
-dontwarn com.epson.**
