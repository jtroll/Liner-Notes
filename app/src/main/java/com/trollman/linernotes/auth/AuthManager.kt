package com.trollman.linernotes.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.trollman.linernotes.R
import com.trollman.linernotes.cloud.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Firebase Auth with Google, through Credential Manager: the same setup as Kerasma. Only made
 * when the build has a Firebase config (AppGraph).
 */
class AuthManager {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val current: FirebaseUser? get() = auth.currentUser

    val currentUser: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** [activityContext] must be an Activity: Credential Manager shows a sheet. Never throws. */
    suspend fun signInWithGoogle(activityContext: Context): SignInOutcome {
        val clientId = activityContext.getString(R.string.default_web_client_id)
        if (clientId.isBlank()) return SignInOutcome.Failed("this build has no Google sign-in set up")
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        return try {
            val credential = CredentialManager.create(activityContext)
                .getCredential(activityContext, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
                SignInOutcome.Success
            } else {
                SignInOutcome.Failed("unexpected credential type")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            Log.e("AuthManager", "No credential offered: check the signing certificate", e)
            SignInOutcome.NotOffered(signingFingerprint(activityContext))
        } catch (e: GetCredentialInterruptedException) {
            SignInOutcome.Unreachable
        } catch (e: Exception) {
            Log.e("AuthManager", "Google sign-in failed", e)
            SignInOutcome.Failed(e::class.simpleName ?: "unknown error")
        }
    }

    fun signOut() = auth.signOut()
}
