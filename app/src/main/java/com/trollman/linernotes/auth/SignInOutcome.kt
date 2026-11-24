package com.trollman.linernotes.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * What happened when someone tapped "Sign in with Google". Kerasma's, word for word in spirit:
 * the ways sign-in fails need telling apart, because "please try again" is bad advice for most.
 */
sealed interface SignInOutcome {
    data object Success : SignInOutcome

    /** The sheet was dismissed. Not an error: say nothing. */
    data object Cancelled : SignInOutcome

    /**
     * Google declined to offer an account. On a sideloaded build this is nearly always the signing
     * certificate: the APK isn't signed with the key registered in Firebase. [signature] is this
     * build's own fingerprint, so it can be compared with docs/SETUP.md at a glance.
     */
    data class NotOffered(val signature: String?) : SignInOutcome

    data object Unreachable : SignInOutcome

    data class Failed(val detail: String) : SignInOutcome

    fun message(): String? = when (this) {
        Success, Cancelled -> null
        is NotOffered -> buildString {
            append("Google didn't offer an account. This build is probably signed with a key ")
            append("Firebase doesn't know (docs/SETUP.md), or the phone has no Google account.")
            if (signature != null) append("\n\nThis build is signed $signature.")
        }
        Unreachable -> "Couldn't reach Google. Check the connection and try again."
        is Failed -> "Couldn't sign in: $detail."
    }
}

/** The running app's signing certificate as a short SHA-1, for error messages. Never throws. */
internal fun signingFingerprint(context: Context): String? = runCatching {
    val pm = context.packageManager
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    }
    val first = signatures?.firstOrNull() ?: return@runCatching null
    val sha1 = MessageDigest.getInstance("SHA-1").digest(first.toByteArray())
        .joinToString("") { "%02x".format(it) }
    "${sha1.take(8)}…${sha1.takeLast(4)}"
}.onFailure { Log.w("AuthManager", "Could not read signing certificate", it) }.getOrNull()
