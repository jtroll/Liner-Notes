package com.trollman.linernotes.update

import android.util.Log
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.trollman.linernotes.BuildConfig
import com.trollman.linernotes.cloud.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A newer build waiting in App Distribution. */
data class Release(val version: String, val code: Long, val notes: String?)

/** Where an update has got to, for Setup's Updates panel. */
data class UpdateState(
    val checking: Boolean = false,
    val available: Release? = null,
    /** True after a check found nothing newer. */
    val upToDate: Boolean = false,
    /** Downloading: 0 to 1, or null before the size is known. */
    val downloading: Boolean = false,
    val progress: Float? = null,
    val problem: String? = null,
)

/**
 * In-app updates (SPEC.md, "Build order", Phase 6) through Firebase App Distribution's own SDK, so
 * they work for anyone App Tester already serves, whether or not the repository is public: the
 * first check signs the person in as a tester (a browser page, once), later checks are quiet, and
 * "Update" downloads the APK and hands it to Android's installer.
 */
class Updates(private val firebaseReady: Boolean) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val dist: FirebaseAppDistribution?
        get() = if (!firebaseReady) null else runCatching { FirebaseAppDistribution.getInstance() }.getOrNull()

    /** At start-up: only for someone already signed in as a tester, so it never puts up a page. */
    suspend fun quietCheck(): Release? {
        val d = dist ?: return null
        if (!runCatching { d.isTesterSignedIn }.getOrDefault(false)) return null
        return check(d, quiet = true)
    }

    /** Setup's "Check for an update". */
    suspend fun check(): Release? {
        val d = dist ?: run {
            _state.update { it.copy(problem = "This build has no Firebase, so it can't check for updates.") }
            return null
        }
        return check(d, quiet = false)
    }

    private suspend fun check(d: FirebaseAppDistribution, quiet: Boolean): Release? {
        _state.update { it.copy(checking = true, problem = null, upToDate = false) }
        return try {
            if (!d.isTesterSignedIn) d.signInTester().await()
            val r = d.checkForNewRelease().await()
            val release = r?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                ?.let { Release(it.displayVersion, it.versionCode, it.releaseNotes?.takeIf { n -> n.isNotBlank() }) }
            _state.update { it.copy(checking = false, available = release, upToDate = release == null) }
            release
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            _state.update { it.copy(checking = false, problem = if (quiet) null else describe(e)) }
            null
        }
    }

    /** Downloads the newer build and opens Android's installer. */
    suspend fun install() {
        val d = dist ?: return
        _state.update { it.copy(downloading = true, progress = null, problem = null) }
        try {
            val task = d.updateApp()
            task.addOnProgressListener { p ->
                val total = p.apkFileTotalBytes
                val done = p.apkBytesDownloaded
                _state.update { it.copy(progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }
            }
            task.await()
            _state.update { it.copy(downloading = false) }
        } catch (e: Exception) {
            Log.w(TAG, "Update failed", e)
            _state.update { it.copy(downloading = false, problem = describe(e)) }
        }
    }

    private fun describe(e: Exception): String {
        val code = (e as? FirebaseAppDistributionException)?.errorCode?.name.orEmpty()
        return when (code) {
            "AUTHENTICATION_CANCELED" -> "Sign-in as a tester was cancelled. Check again to finish it."
            "AUTHENTICATION_FAILURE" -> "Couldn't sign in as a tester. Is this Google account on the testers list?"
            "NETWORK_FAILURE" -> "No connection. Try again in a moment."
            "API_DISABLED" -> "Updates aren't switched on for this Firebase project yet (docs/SETUP.md)."
            "NOT_IMPLEMENTED" -> "This build can't install updates itself. App Tester still can."
            "INSTALLATION_CANCELED" -> "The install was cancelled. Update again whenever you like."
            "UPDATE_NOT_AVAILABLE" -> "There's nothing newer right now."
            "DOWNLOAD_FAILURE" -> "The download didn't finish. Try again."
            "INSTALLATION_FAILURE" -> "Android couldn't install it. App Tester can, as before."
            else -> "The update didn't work: ${e.message ?: "unknown problem"}"
        }
    }

    private companion object {
        const val TAG = "Updates"
    }
}
