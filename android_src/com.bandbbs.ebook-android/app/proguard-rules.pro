############################################
# 基础
############################################
-dontwarn **
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes KotlinMetadata

############################################
# Kotlin / 协程
############################################
-keep class kotlinx.coroutines.** { *; }

############################################
# Kotlinx Serialization（FileMessagesToSend/FileMessagesFromDevice 等数据类）
############################################
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class com.whyy.snapnotes.**$$serializer { *; }

############################################
# XMS wearable SDK 内部反射
############################################
-keep class com.xiaomi.xms.wearable.** { *; }
-dontwarn com.xiaomi.xms.wearable.**

############################################
# 🔥 关键修复：Room 数据库 + WorkManager（解决 Release 包闪退）
############################################
# 保留所有 RoomDatabase 的子类（包括编译时生成的 _Impl 类）
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# 保留使用了 @Database 注解的类及其构造方法
-keep @androidx.room.Database class * { *; }
-keepclasseswithmembers class * {
    @androidx.room.Database <init>(...);
}

# 特别保护 WorkManager 的数据库（崩溃点）
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(...); }

# 通用规则：保留所有 Room 生成的 _Impl 类的构造方法
-keep class **._Impl {
    <init>(...);
}

# 保留 Room 相关的所有内部类（防止反射失败）
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.db.** { *; }

# WorkManager 全套保护
-keep class androidx.work.** { *; }
-keep interface androidx.work.** { *; }
-dontwarn androidx.work.**

############################################
# 安全：Release 构建移除全部日志调用（防 logcat 泄漏敏感内容）
############################################
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int w(java.lang.String, java.lang.String);
    public static int w(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int e(java.lang.String, java.lang.String);
    public static int e(java.lang.String, java.lang.String, java.lang.Throwable);
}