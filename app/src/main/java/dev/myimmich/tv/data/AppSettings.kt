package dev.myimmich.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

private val dreamJson = Json { ignoreUnknownKeys = true }

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    val certFingerprint: String,
    val trustAny: Boolean,
    /** Pins for the issuing CA(s) seen at setup (DER + SPKI fingerprints). Leaves may rotate; these hold. */
    val caPins: List<String> = emptyList(),
)

class AppSettings(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val certFingerprint = stringPreferencesKey("cert_fingerprint")
        val caPins = stringPreferencesKey("ca_pins")
        val trustAny = booleanPreferencesKey("trust_any")
        val slideshowSeconds = intPreferencesKey("slideshow_seconds")
        val slideshowShuffle = booleanPreferencesKey("slideshow_shuffle")
        val highQuality = booleanPreferencesKey("high_quality")
        val remoteEnabled = booleanPreferencesKey("remote_enabled")
        val dreamSourceId = stringPreferencesKey("dream_source_id")
        val dreamSourceName = stringPreferencesKey("dream_source_name")
        val dreamSources = stringPreferencesKey("dream_sources")
        val dreamSeconds = intPreferencesKey("dream_seconds")
        val dreamIncludeVideos = booleanPreferencesKey("dream_include_videos")
        val dreamShowInfo = booleanPreferencesKey("dream_show_info")
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
                caPins = p[Keys.caPins]?.let { raw ->
                    runCatching { dreamJson.decodeFromString<List<String>>(raw) }
                        .getOrDefault(emptyList())
                } ?: emptyList(),
            )
        }
    }

    val highQuality: Flow<Boolean> = context.dataStore.data.map { it[Keys.highQuality] ?: false }
    val slideshowSeconds: Flow<Int> = context.dataStore.data.map { it[Keys.slideshowSeconds] ?: 10 }
    val slideshowShuffle: Flow<Boolean> = context.dataStore.data.map { it[Keys.slideshowShuffle] ?: false }
    val remoteEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.remoteEnabled] ?: true }

    /** Screensaver sources; empty list = timeline (default). Migrates the old single-source keys. */
    val dreamSources: Flow<List<DreamSource>> = context.dataStore.data.map { p ->
        val raw = p[Keys.dreamSources]
        if (!raw.isNullOrBlank()) {
            runCatching { dreamJson.decodeFromString<List<DreamSource>>(raw) }.getOrDefault(emptyList())
        } else {
            val legacyId = p[Keys.dreamSourceId] ?: ""
            val legacyName = p[Keys.dreamSourceName] ?: ""
            when {
                legacyId.isBlank() -> emptyList()
                legacyId == "favorites" -> listOf(DreamSource("favorites"))
                else -> listOf(DreamSource("album", legacyId, legacyName.ifBlank { null }))
            }
        }
    }
    val dreamSeconds: Flow<Int> = context.dataStore.data.map { it[Keys.dreamSeconds] ?: 10 }
    val dreamIncludeVideos: Flow<Boolean> = context.dataStore.data.map { it[Keys.dreamIncludeVideos] ?: false }
    val dreamShowInfo: Flow<Boolean> = context.dataStore.data.map { it[Keys.dreamShowInfo] ?: false }

    suspend fun saveServer(config: ServerConfig) {
        context.dataStore.edit { p ->
            p[Keys.serverUrl] = config.serverUrl
            p[Keys.apiKey] = config.apiKey
            p[Keys.certFingerprint] = config.certFingerprint
            p[Keys.trustAny] = config.trustAny
            if (config.caPins.isEmpty()) {
                p.remove(Keys.caPins)
            } else {
                p[Keys.caPins] = dreamJson.encodeToString(config.caPins)
            }
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit { p ->
            p.remove(Keys.serverUrl)
            p.remove(Keys.apiKey)
            p.remove(Keys.certFingerprint)
            p.remove(Keys.trustAny)
            p.remove(Keys.caPins)
        }
    }

    suspend fun setHighQuality(value: Boolean) = context.dataStore.edit { it[Keys.highQuality] = value }
    suspend fun setSlideshow(seconds: Int, shuffle: Boolean) = context.dataStore.edit {
        it[Keys.slideshowSeconds] = seconds.coerceIn(3, 120)
        it[Keys.slideshowShuffle] = shuffle
    }

    suspend fun setSlideshowSeconds(seconds: Int) = context.dataStore.edit {
        it[Keys.slideshowSeconds] = seconds.coerceIn(3, 120)
    }

    suspend fun setDreamSources(sources: List<DreamSource>) = context.dataStore.edit {
        it[Keys.dreamSources] = dreamJson.encodeToString(sources)
    }

    suspend fun setDreamSeconds(seconds: Int) = context.dataStore.edit {
        it[Keys.dreamSeconds] = seconds.coerceIn(5, 300)
    }

    suspend fun setDreamIncludeVideos(value: Boolean) = context.dataStore.edit {
        it[Keys.dreamIncludeVideos] = value
    }

    suspend fun setDreamShowInfo(value: Boolean) = context.dataStore.edit {
        it[Keys.dreamShowInfo] = value
    }
}
