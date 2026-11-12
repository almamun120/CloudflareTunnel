# JNI
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.cloudflare.tunnel.TunnelManager { *; }
# Parcelable / model classes
-keep class com.cloudflare.tunnel.** implements android.os.Parcelable { *; }
# NanoHTTPD, XZ, ZXing
-keep class fi.iki.elonen.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn com.google.zxing.**
