# Keep JavascriptInterface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class net.koboldai.lite.WebAppInterface {
    <methods>;
}
