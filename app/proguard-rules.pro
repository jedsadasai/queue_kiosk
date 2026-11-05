# Gson DTOs are read via reflection
-keep class com.queuekiosk.data.api.** { *; }
-keepattributes Signature, *Annotation*
# Optional Epson driver is loaded with Class.forName
-keep class com.queuekiosk.printer.epson.EpsonEpos2Driver { public <init>(android.content.Context); }
-dontwarn com.epson.**
