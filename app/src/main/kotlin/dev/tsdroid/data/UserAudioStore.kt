package dev.tsdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlin.math.pow

private val Context.userAudioDataStore by preferencesDataStore(name = "user_audio")

/**
 * Persisted per-user audio settings, keyed by server address + permanent
 * client uid (client ids are reassigned every connection, so they must never
 * be persisted). A null setter removes the entry, meaning "default".
 */
class UserAudioStore(private val context: Context) {

    /**
     * Per-user volume as a linear multiplier (1.0 = unity). Legacy installs
     * stored dB — converted on read; the legacy key is dropped on next write.
     */
    suspend fun volumeFactor(address: String, uid: String): Float? {
        val prefs = context.userAudioDataStore.data.first()
        prefs[volumeFactorKey(address, uid)]?.let { return it }
        return prefs[volumeKey(address, uid)]?.let { db -> 10f.pow(db / 20f) }
    }

    suspend fun setVolumeFactor(address: String, uid: String, factor: Float?) {
        context.userAudioDataStore.edit { prefs ->
            prefs.remove(volumeKey(address, uid))
            if (factor == null) prefs.remove(volumeFactorKey(address, uid)) else prefs[volumeFactorKey(address, uid)] = factor
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

    private fun volumeFactorKey(address: String, uid: String) =
        floatPreferencesKey("volume_factor|$address|$uid")

    private fun mutedKey(address: String, uid: String) =
        booleanPreferencesKey("muted|$address|$uid")
}
