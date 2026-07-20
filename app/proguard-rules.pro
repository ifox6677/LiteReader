# ============================================================================
# LiteReader ProGuard / R8 规则
# 仅 release 构建生效，debug 构建不走 R8。
# ----------------------------------------------------------------------------

# ============================================================================
# 1. JNI 桥接类（最关键，错失任一类/方法/字段 → 应用启动或阅读时崩溃）
#    Rust 侧通过精确类名 + 方法名调用 Kotlin：
#      Java_com_example_litereader_reader_RustEpubEngine_openBook
#      Java_com_example_litereader_reader_RustPdfEngine_loadPdfImage
#      Java_com_example_litereader_reader_RustTxtEngine_loadTxtChapter
#    类名、外部方法名、嵌套 BookInfo/ChapterInfo 字段都必须原样保留。
# ============================================================================
-keep class com.example.litereader.reader.RustEpubEngine { *; }
-keep class com.example.litereader.reader.RustEpubEngine$* { *; }
-keep class com.example.litereader.reader.RustPdfEngine { *; }
-keep class com.example.litereader.reader.RustPdfEngine$* { *; }
-keep class com.example.litereader.reader.RustTxtEngine { *; }
-keep class com.example.litereader.reader.RustTxtEngine$* { *; }

# 通用兜底：所有含 native 方法的类与 native 方法签名不可重命名
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================================
# 2. Room：实体类、DAO、RoomDatabase 子类通过反射访问
#    - KSP 生成的 Dao_Impl 在运行时通过反射访问实体字段
#    - RoomDatabase 子类被 Room 框架反射实例化
# ============================================================================
-keep class com.example.litereader.domain.model.Book { *; }
-keep class com.example.litereader.data.local.BookDao { *; }
-keep class com.example.litereader.data.local.BookDatabase { *; }
-keep class com.example.litereader.data.local.BookDatabase$* { *; }

# 通用 Room 规则（兜底）
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# ============================================================================
# 3. Domain 模型：Block / Chapter
#    Block 为 sealed class，JSON 解析虽手动，但子类用于 when 分发，
#    保留类名与字段名避免 R8 inline 导致类型识别异常。
# ============================================================================
-keep class com.example.litereader.domain.model.Block { *; }
-keep class com.example.litereader.domain.model.Block$* { *; }
-keep class com.example.litereader.domain.model.Chapter { *; }

# ============================================================================
# 4. Kotlin 元数据与序列化
#    Block/Chapter 实现 Serializable，需要保留序列化字段。
# ============================================================================
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod,Deprecated

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================================
# 5. Compose / AndroidX 自带 consumer rules 通常已足够，
#    但 ViewModel 通过 viewModels() 反射实例化，需保留构造器。
# ============================================================================
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ============================================================================
# 6. OkHttp / Jsoup / Coil：依赖自带 consumer-rules.pro，无需手动配置。
#    这里仅保留一个兜底，防止 R8 警告。
# ============================================================================
-dontwarn okhttp3.internal.platform.**
-dontwarn org.jsoup.**
-dontwarn coil.**

# ============================================================================
# 7. Kotlin Coroutines 内部类（部分版本需要）
# ============================================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
