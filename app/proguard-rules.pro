# Proguard rules for Kiosk app
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.cinarli.kiosk.** { *; }
