# kotlinx-serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class io.dilfi5h.agentping.** { kotlinx.serialization.KSerializer serializer(...); }
