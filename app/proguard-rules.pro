# 保留行号，崩溃堆栈仍可定位（release 也可用）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.opencode.client.**$$serializer { *; }
-keepclassmembers class dev.opencode.client.** {
    *** Companion;
}
-keepclasseswithmembers class dev.opencode.client.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- Markwon ----------
-keep class io.noties.markwon.** { *; }
-keep class io.noties.prism4j.** { *; }
-dontwarn io.noties.**

# ---------- Jetpack Compose ----------
# Compose 运行时依赖反射处理部分类，保留必要信息
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ---------- 应用入口 ----------
-keep class dev.opencode.client.OpenCodeApp { *; }
-keep class dev.opencode.client.MainActivity { *; }

# 保留枚举与 Parcelable
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
