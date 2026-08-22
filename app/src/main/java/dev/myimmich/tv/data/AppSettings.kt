package dev.myimmich.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    val certFingerprint: String,
    val trustAny: Boolean,
)

class AppSettings(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val certFingerprint = stringPreferencesKey("cert_fingerprint")
        val trustAny = booleanPreferencesKey("trust_any")
        val slideshowSeconds = intPreferencesKey("slideshow_seconds")
        val slideshowShuffle = booleanPreferencesKey("slideshow_shuffle")
        val highQuality = booleanPreferencesKey("high_quality")
        val remoteEnabled = booleanPreferencesKey("remote_enabled")
    }

    val serverConfig: Flow<ServerConfig?> = context.dataStore.data.map { p ->
        val url = p[Keys.serverUrl]
        val key = p[Keys.apiKey]
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            null
        } else {
            ServerConfig(
                serverUrl = url.trimEnd('/'),
                apiKey = key,
                certFingerprint = p[Keys.certFingerprint] ?: "",
                trustAny = p[Keys.trustAny] ?: false,
            )
        }
    }

    val highQuality: Flow<Boolean> = context.dataStore.data.map { it[Keys.highQuality] ?: false }
    val slideshowSeconds: Flow<Int> = context.dataStore.data.map { it[Keys.slideshowSeconds] ?: 10 }
    val slideshowShuffle: Flow<Boolean> = context.dataStore.data.map { it[Keys.slideshowShuffle] ?: false }
    val remoteEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.remoteEnabled] ?: true }

    suspend fun saveServer(config: ServerConfig) {
        context.dataStore.edit { p ->
            p[Keys.serverUrl] = config.serverUrl
            p[Keys.apiKey] = config.apiKey
            p[Keys.certFingerprint] = config.certFingerprint
            p[Keys.trustAny] = config.trustAny
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit { p ->
            p.remove(Keys.serverUrl)
            p.remove(Keys.apiKey)
            p.remove(Keys.certFingerprint)
            p.remove(Keys.trustAny)
        }
    }

    suspend fun setHighQuality(value: Boolean) = context.dataStore.edit { it[Keys.highQuality] = value }
    suspend fun setSlideshow(seconds: Int, shuffle: Boolean) = context.dataStore.edit {
        it[Keys.slideshowSeconds] = seconds.coerceIn(3, 120)
        it[Keys.slideshowShuffle] = shuffle
    }
}
