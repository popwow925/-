# JNI: native code calls these by name
-keep class cn.eririlily.launcher.game.Bridge { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class cn.eririlily.launcher.WebBridge { *; }
