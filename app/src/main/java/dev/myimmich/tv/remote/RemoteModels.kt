package dev.myimmich.tv.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteCommand(
    val type: String,
    val assetId: String? = null,
    val assetType: String? = null,
    val value: Int? = null,
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
)
