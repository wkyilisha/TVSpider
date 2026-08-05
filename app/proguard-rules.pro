# Merge
-flattenpackagehierarchy com.github.catvod.spider.merge
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Spider & CatVod Core
-keep class com.github.catvod.js.** { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class com.github.catvod.spider.* { public <methods>; }
-keep class com.github.catvod.parser.* { public <methods>; }

# 新增底层网络与工具类保护（TLS伪造、Frida RPC、JsUtil）
-keep class com.github.catvod.net.CustomTLSSocketFactory { *; }
-keep class com.github.catvod.net.RpcClient { *; }
-keep class com.github.catvod.utils.JsUtil { *; }

# AndroidX
-keep class androidx.core.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# Rhino JS Engine (替换原有的 QuickJS)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Logger
-keep class com.orhanobut.logger.** { *; }

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# Smbj
-dontwarn org.xmlpull.v1.**
-dontwarn android.content.res.**
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }

# Zxing
-keep class com.google.zxing.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Suppress warnings
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider

-keepattributes SourceFile,LineNumberTable