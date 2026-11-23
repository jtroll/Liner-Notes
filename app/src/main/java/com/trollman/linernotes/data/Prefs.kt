package com.trollman.linernotes.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "liner_notes")

/** The few things kept on the phone itself. Everything that matters lives in Firestore. */
class Prefs(context: Context) {
    private val store = context.applicationContext.store
    private val onboarded = booleanPreferencesKey("connections_seen")
    private val period = stringPreferencesKey("shelf_period")
    private val invite = stringPreferencesKey("pending_invite")

    /** Whether the first-run connections screen has been finished or skipped. */
    val connectionsSeen: Flow<Boolean> = store.data.map { it[onboarded] ?: false }
    val shelfPeriod: Flow<Period> = store.data.map { p ->
        Period.entries.firstOrNull { it.name == p[period] } ?: Period.Quarter
    }

    /**
     * An invite link opened before the person had signed in. Kept on the phone so it survives the
     * sign-in and the first-run screens, then offered once they're in.
     */
    val pendingInvite: Flow<String?> = store.data.map { it[invite] }

    suspend fun setPendingInvite(token: String?) = store.edit {
        if (token == null) it.remove(invite) else it[invite] = token
    }

    suspend fun setConnectionsSeen(value: Boolean) = store.edit { it[onboarded] = value }
    suspend fun setShelfPeriod(value: Period) = store.edit { it[period] = value.name }
}
