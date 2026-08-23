package dev.myimmich.tv.data

import kotlinx.serialization.Serializable

/** A screensaver source. kind: "timeline" | "favorites" | "album" (id/name set for albums). */
@Serializable
data class DreamSource(
    val kind: String,
    val id: String? = null,
    val name: String? = null,
)
