// Imported rather than written as java.util.Properties: inside a .gradle.kts script `java`
// resolves to Gradle's own java extension (the same note as Kerasma's build file).
import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---- Firebase config -------------------------------------------------------------------------
// app/google-services.json is downloaded from the Firebase console once (docs/SETUP.md) and
// committed, as Kerasma does. Until it exists the app still builds and runs, in "setup needed"
// mode: no sign-in, and a demo shelf. The google-services plugin refuses to build without the
// file, so it is applied only when the file is there.
val googleServices = file("google-services.json")
val hasFirebaseConfig = googleServices.exists()
if (hasFirebaseConfig) apply(plugin = "com.google.gms.google-services")

val firebaseProjectId: String = if (hasFirebaseConfig) {
    val parsed = JsonSlurper().parse(googleServices) as Map<*, *>
    ((parsed["project_info"] as? Map<*, *>)?.get("project_id") as? String).orEmpty()
} else ""

// ---- Public client values --------------------------------------------------------------------
// The Spotify client ID and the Last.fm API key are not secrets (they identify the app, and both
// services expect them inside a mobile app). The secrets that go with them live only in Google
// Secret Manager, read by Cloud Functions. Each value is read, first found wins, from:
//   1. a Gradle property  (-PSPOTIFY_CLIENT_ID=...)
//   2. an environment variable (CI fills these from the repository's Actions variables)
//   3. linernotes.local.properties beside this file (gitignored), for a local build
val localValues = Properties().apply {
    val f = file("linernotes.local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun clientValue(name: String): String =
    (providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localValues.getProperty(name)
        ?: "").trim()

val spotifyClientId = clientValue("SPOTIFY_CLIENT_ID")
val lastfmApiKey = clientValue("LASTFM_API_KEY")
// Where Last.fm sends people back after they approve the app: a page on Firebase Hosting that
// hands the token to the app (hosting/lastfm-callback/index.html). Overridable for a custom domain.
val lastfmCallbackUrl = clientValue("LASTFM_CALLBACK_URL").ifEmpty {
    if (firebaseProjectId.isNotEmpty()) "https://$firebaseProjectId.web.app/lastfm-callback" else ""
}

// One number per CI run, so every build App Tester offers reads as newer than the last.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.trollman.linernotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trollman.linernotes"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", quoted(spotifyClientId))
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", quoted("linernotes://spotify-callback"))
        buildConfigField("String", "LASTFM_API_KEY", quoted(lastfmApiKey))
        buildConfigField("String", "LASTFM_CALLBACK_URL", quoted(lastfmCallbackUrl))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(firebaseProjectId))
        buildConfigField("boolean", "HAS_FIREBASE_CONFIG", hasFirebaseConfig.toString())

        // The google-services plugin writes default_web_client_id from google-services.json.
        // Without that file nothing would, and AuthManager's reference wouldn't compile.
        if (!hasFirebaseConfig) resValue("string", "default_web_client_id", "")
    }

    signingConfigs {
        getByName("debug") {
            // CI restores J.T.'s debug keystore (the same key as Kerasma's) from the
            // DEBUG_KEYSTORE_BASE64 secret and names it here. It must be stated outright: a
            // keystore dropped in ~/.android on a runner is silently ignored and every build gets
            // a throwaway key, which Google sign-in and Spotify both refuse. Kerasma's
            // docs/APK_SHARING.md has the story. Unset, AGP's own default applies.
            val explicit = System.getenv("LINERNOTES_DEBUG_KEYSTORE")
            if (!explicit.isNullOrBlank() && File(explicit).exists()) {
                storeFile = file(explicit)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.pickFirsts += listOf("META-INF/LICENSE", "META-INF/NOTICE")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Custom Tabs, for Last.fm's approval page.
    implementation("androidx.browser:browser:1.8.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // Each album's accent colour, pulled from its cover.
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- Firebase (same BoM as Kerasma) ----
    implementation(platform("com.google.firebase:firebase-bom:34.14.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")

    // ---- Google sign-in via Credential Manager (as Kerasma) ----
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // ---- Spotify ----
    // Sign-in for the Web API (playlists). 5.0.0 no longer declares its redirect intent-filter;
    // AndroidManifest.xml does.
    implementation("com.spotify.android:auth:5.0.0")
    // App Remote: plays albums through the Spotify app on the phone. Spotify ships it only as an
    // AAR in github.com/spotify/android-sdk (Apache 2.0, licences/), not on Maven, so it is
    // checked in. It reads its JSON with Gson.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
