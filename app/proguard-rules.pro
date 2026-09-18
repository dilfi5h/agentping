# R8 rules for the minified release build (isMinifyEnabled = true in app/build.gradle.kts).
# Room, OkHttp and kotlinx-serialization ship their own consumer rules, so this file only covers the
# app-side reflection the libraries cannot see.

# Obfuscated names still need usable stack traces: keep the line table and rewrite the file name so
# "at a.b.c(SourceFile:123)" can be decoded with app/build/outputs/mapping/release/mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx-serialization: the reified Json.decodeFromString<T>() lookups and @Serializable deduction
# read annotations at runtime, and the generated serializers are reached through the companion.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class io.dilfi5h.agentping.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.dilfi5h.agentping.**$$serializer { *; }

# Room: the generated AppDatabase_Impl is resolved by name at runtime; entities are kept so a schema
# mismatch can never be caused by shrinking (Room's own rules only cover RoomDatabase subclasses).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
