# offreel release shrinking rules.

# youtubedl-android (yt-dlp + ffmpeg) ships no rules of its own and parses yt-dlp's JSON with
# Jackson through reflection. Keep it whole; it's small next to the Python payload.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# It unpacks its Python/ffmpeg payload with Apache Commons Compress, which builds registries of its
# own classes by reflection at start-up ("class X is not a concrete class" when shrunk).
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**

# Better crash reports from release builds.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
