-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.taxi.meter.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.taxi.meter.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
