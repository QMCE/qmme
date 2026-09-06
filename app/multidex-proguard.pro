# ── qq-sdk: 主 dex 提前加载需要的类（内核反射、MSF、QRoute 动态加载） ──
-keep class com.tencent.** { *; }
-keep class mqq.** { *; }
-keep class oicq.** { *; }
-keep class d.c.g.** { *; }
-keep class com.tencent.qqnt.watch.** { *; }

# Required during MSF startup before secondary dex loading is available.
-keep class com.tencent.mobileqq.msf.sdk.AppNetConnInfo { *; }
-dontwarn com.tencent.**
-dontwarn mqq.**
-dontwarn oicq.**

# ── 宿主代码 ──
-keep class * extends com.tencent.mobileqq.pb.MessageMicro { *; }
-keep class rj.qmme.QmmeApp { *; }
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class com.tencent.mobileqq.qfix.ApplicationDelegate { *; }
-keep class * extends com.tencent.mobileqq.qfix.ApplicationDelegate { *; }
-keep class android.** { *; }

# ── Flag, called by QLog ──
-keep class rj.qmme.Flag { *; }

# ── multidex ──
-keep class com.bytedance.** { *; }

# ── Kotlin ──
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

# ── JNI / native ──
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 通用 ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
