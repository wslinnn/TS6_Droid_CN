package dev.tsdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.userAudioDataStore by preferencesDataStore(name = "user_audio")

/**
 * Persisted per-user audio settings, keyed by server address + permanent
 * client uid (client ids are reassigned every connection, so they must never
 * be persisted). A null setter removes the entry, meaning "default".
 */
class UserAudioStore(private val context: Context) {

    suspend fun volumeDb(address: String, uid: String): Float? =
        context.userAudioDataStore.data.first()[volumeKey(address, uid)]

    suspend fun setVolumeDb(address: String, uid: String, db: Float?) {
        context.userAudioDataStore.edit { prefs ->
            if (db == null) prefs.remove(volumeKey(address, uid)) else prefs[volumeKey(address, uid)] = db
        }
    }

    suspend fun muted(address: String, uid: String): Boolean? =
        context.userAudioDataStore.data.first()[mutedKey(address, uid)]

    suspend fun setMuted(address: String, uid: String, muted: Boolean?) {
        context.userAudioDataStore.edit { prefs ->
            if (muted == null) prefs.remove(mutedKey(address, uid)) else prefs[mutedKey(address, uid)] = muted
        }
    }

    private fun volumeKey(address: String, uid: String) =
        floatPreferencesKey("volume|$address|$uid")

    private fun mutedKey(address: String, uid: String) =
        booleanPreferencesKey("muted|$address|$uid")
}
