# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.trollman.linernotes.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.trollman.linernotes.**$$serializer { *; }

# Spotify App Remote (its own rules ship in the AAR; Gson reads these by reflection)
-keep class com.spotify.protocol.** { *; }
-dontwarn com.spotify.base.annotations.**
