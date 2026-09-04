# Kotlinx Serialization: keep serializer classes for our data models.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.notepadpro.**$$serializer { *; }
-keepclassmembers class com.notepadpro.** {
    *** Companion;
}
-keepclasseswithmembers class com.notepadpro.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLDelight generated code is referenced reflectively by the driver
-dontwarn app.cash.sqldelight.**
-keep class com.notepadpro.shared.data.db.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Compose / activity
-dontwarn androidx.compose.**
