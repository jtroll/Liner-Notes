// Same plugin versions as Kerasma (jtroll/kerasma), so the two apps build the same way.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}
