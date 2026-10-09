# The app has no reflected Java model. Keep only JavaScript bridge methods if
# one is added in the future.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.havalh6.viewer.MediaNotificationListener
# Shizuku loads the reboot user service by class name in its own process.
-keep class com.havalh6.viewer.GwmRebootService { public <init>(); public *; }
-keep class com.havalh6.viewer.IGwmRebootService { *; }
-keep class com.havalh6.viewer.IGwmRebootService$Stub { *; }
-keep class com.havalh6.viewer.IGwmRebootService$Stub$Proxy { *; }
