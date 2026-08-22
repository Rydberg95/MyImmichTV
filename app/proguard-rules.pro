-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.myimmich.tv.** {
    *** Companion;
}
-keepclasseswithmembers class dev.myimmich.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
