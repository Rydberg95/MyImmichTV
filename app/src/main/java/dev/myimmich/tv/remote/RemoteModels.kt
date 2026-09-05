package dev.myimmich.tv.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteCommand(
    val type: String,
    val assetId: String? = null,
    val assetType: String? = null,
    val value: Int? = null,
    val context: RemoteCommandContext? = null,
)

@Serializable
data class RemoteCommandContext(
    val source: String,
    val albumId: String? = null,
    val albumName: String? = null,
    val personId: String? = null,
    val personName: String? = null,
    val bucket: String? = null,
    val assets: List<RemoteAsset>? = null,
)

@Serializable
data class RemoteViewerState(
    val source: String,
    val label: String,
    val index: Int,
    val total: Int,
    val assetId: String? = null,
    val assetType: String? = null,
    val slideshow: Boolean = false,
    val shuffle: Boolean = false,
    val certChanged: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class PairResponse(
    val token: String,
    val serverName: String,
)

@Serializable
data class RemoteAlbum(
    val id: String,
    val name: String,
    val count: Int,
    val thumbId: String? = null,
)

@Serializable
data class RemoteAsset(
    val id: String,
    val type: String,
    val fileCreatedAt: String? = null,
    val isFavorite: Boolean = false,
    val durationMs: Long? = null,
    val livePhotoVideoId: String? = null,
    val thumbhash: String? = null,
    val city: String? = null,
    val country: String? = null,
)

@Serializable
data class RemotePerson(
    val id: String,
    val name: String,
    val faceUrl: String? = null,
)

fun RemoteAsset.toAssetDto() = dev.myimmich.tv.api.AssetDto(
    id = id,
    type = type,
    fileCreatedAt = fileCreatedAt,
    isFavorite = isFavorite,
    durationMs = durationMs,
    livePhotoVideoId = livePhotoVideoId,
    thumbhash = thumbhash,
    city = city,
    country = country,
)

@Serializable
data class SetupState(
    val phase: String = "WAITING",
    val url: String? = null,
    val apiKeyMasked: String? = null,
    val fingerprint: String? = null,
    val subject: String? = null,
    val issuer: String? = null,
    val caIssuer: String? = null,
    val caFingerprint: String? = null,
    val error: String? = null,
    val configured: Boolean = false,
)

/** Live view of the server's currently presented certificate, probed on demand. */
@Serializable
data class CertCheckDto(
    /** True when the presented chain matches none of the stored pins. */
    val changed: Boolean,
    val fingerprint: String? = null,
    val subject: String? = null,
    val issuer: String? = null,
    val caIssuer: String? = null,
    /** SPKI SHA-256 of the issuing CA — the value that stays stable across leaf rotations. */
    val caFingerprint: String? = null,
    val error: String? = null,
)

@Serializable
data class CertConfirmDto(
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class SetupSubmitDto(
    val pin: String,
    val url: String,
    val apiKey: String,
)

@Serializable
data class SetupPinDto(
    val pin: String,
)

@Serializable
data class AppStatusDto(
    val configured: Boolean,
    val name: String,
)

@Serializable
data class SettingsDto(
    val slideshowSeconds: Int,
    val dreamSeconds: Int,
    val dreamIncludeVideos: Boolean,
    val dreamShowInfo: Boolean,
    val gridColumns: Int,
    val dreamSources: List<dev.myimmich.tv.data.DreamSource>,
)

/** Partial update: only non-null fields are applied. */
@Serializable
data class SettingsUpdateDto(
    val slideshowSeconds: Int? = null,
    val dreamSeconds: Int? = null,
    val dreamIncludeVideos: Boolean? = null,
    val dreamShowInfo: Boolean? = null,
    val gridColumns: Int? = null,
    val dreamSources: List<dev.myimmich.tv.data.DreamSource>? = null,
)
