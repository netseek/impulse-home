# The app has no reflected Java model. Keep only JavaScript bridge methods if
# one is added in the future.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
