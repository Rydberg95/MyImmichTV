package dev.myimmich.tv.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    val email: String? = null,
)

@Serializable
data class TimeBucketDto(
    @SerialName("timeBucket") val bucket: String,
    val count: Int = 0,
)

@Serializable
data class TimelineBucketColumnsDto(
    val id: List<String> = emptyList(),
    val isImage: List<Boolean?> = emptyList(),
    val isFavorite: List<Boolean?> = emptyList(),
    val isTrashed: List<Boolean?> = emptyList(),
    val duration: List<Long?> = emptyList(),
    val livePhotoVideoId: List<String?> = emptyList(),
    val fileCreatedAt: List<String?> = emptyList(),
    val thumbhash: List<String?> = emptyList(),
    val city: List<String?> = emptyList(),
    val country: List<String?> = emptyList(),
)

data class AssetDto(
    val id: String,
    val type: String,
    val fileCreatedAt: String?,
    val isFavorite: Boolean,
    val durationMs: Long?,
    val livePhotoVideoId: String?,
    val thumbhash: String?,
    val city: String?,
    val country: String?,
) {
    val isVideo: Boolean get() = type.equals("VIDEO", ignoreCase = true)

    companion object {
        fun fromColumns(c: TimelineBucketColumnsDto): List<AssetDto> {
            val n = c.id.size
            fun <T> List<T>.at(i: Int): T? = if (i < size) get(i) else null
            return (0 until n).map { i ->
                val isImage = c.isImage.at(i) ?: true
                AssetDto(
                    id = c.id[i],
                    type = if (isImage) "IMAGE" else "VIDEO",
                    fileCreatedAt = c.fileCreatedAt.at(i),
                    isFavorite = c.isFavorite.at(i) ?: false,
                    durationMs = c.duration.at(i),
                    livePhotoVideoId = c.livePhotoVideoId.at(i),
                    thumbhash = c.thumbhash.at(i),
                    city = c.city.at(i),
                    country = c.country.at(i),
                )
            }
        }
    }
}

@Serializable
data class AlbumDto(
    val id: String,
    val albumName: String,
    val assetCount: Int = 0,
)

@Serializable
data class SearchRequestDto(
    val query: String,
    val limit: Int = 60,
    val page: Int = 1,
)

@Serializable
data class SearchExifDto(
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)

@Serializable
data class SearchAssetDto(
    val id: String,
    val type: String = "IMAGE",
    val originalFileName: String? = null,
    val fileCreatedAt: String? = null,
    val isFavorite: Boolean = false,
    val duration: kotlinx.serialization.json.JsonElement? = null,
    val livePhotoVideoId: String? = null,
    val thumbhash: String? = null,
    val exifInfo: SearchExifDto? = null,
)

@Serializable
data class SearchAssetsPageDto(
    val total: Int = 0,
    val count: Int = 0,
    val items: List<SearchAssetDto> = emptyList(),
)

@Serializable
data class SmartSearchResponseDto(
    val assets: SearchAssetsPageDto = SearchAssetsPageDto(),
)

@Serializable
data class PersonDto(
    val id: String,
    val name: String? = null,
)

@Serializable
data class PeopleResponseDto(
    val people: List<PersonDto> = emptyList(),
)

/** Tolerant shape of GET /api/assets/{id}; every field may be absent. */
@Serializable
data class ExifInfoDto(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null,
    val fNumber: Double? = null,
    val exposureTime: kotlinx.serialization.json.JsonElement? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val description: String? = null,
    val fileSize: Long? = null,
)

@Serializable
data class AssetDetailDto(
    val id: String = "",
    val type: String? = null,
    val originalFileName: String? = null,
    val fileCreatedAt: String? = null,
    /** Wall-clock time in the photo's own timezone (Immich serializes it with a fake 'Z').
     *  fileCreatedAt/bucket dates are UTC wall-clocks — NOT for display. */
    val localDateTime: String? = null,
    val duration: kotlinx.serialization.json.JsonElement? = null,
    val isFavorite: Boolean? = null,
    val exifInfo: ExifInfoDto? = null,
)

/** Extracts the wall-clock part of Immich timestamps ("…T16:16:42.027Z", "…+00:00", or naive). */
internal fun parseWallClock(raw: String): java.time.LocalDateTime? {
    val m = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?)""").find(raw) ?: return null
    return runCatching { java.time.LocalDateTime.parse(m.value) }.getOrNull()
}
