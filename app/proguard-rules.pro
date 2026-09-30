# Gson: keep models serialized via reflection
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class ru.mybudget.app.data.** { <fields>; }
-keepclassmembers class ru.mybudget.app.BackupModels$* { <fields>; }
-keep class ru.mybudget.app.BackupModels$* { <fields>; }
-keep class ru.mybudget.app.security.BackupCrypto$EncryptedBackupWrapper { <fields>; }

# Kotlin coroutines metadata (R8 handles most, keep annotations for safety)
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
