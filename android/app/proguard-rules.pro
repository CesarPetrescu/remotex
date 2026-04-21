# Keep Kotlin serialization generated classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if class **.*$Companion { kotlinx.serialization.KSerializer serializer(...); }
-keepclasseswithmembers class <1>.<2> {
    <1>.<2>$Companion Companion;
}

# OkHttp — the library emits the needed rules itself via consumerProguardRules.
