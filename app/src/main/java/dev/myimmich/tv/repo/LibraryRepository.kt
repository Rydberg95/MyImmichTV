package dev.myimmich.tv.repo

import dev.myimmich.tv.api.AlbumDto
import dev.myimmich.tv.api.AssetDto
import dev.myimmich.tv.api.ImmichClient
import dev.myimmich.tv.api.TimeBucketDto

class LibraryRepository(private val client: ImmichClient) {

    suspend fun timelineBuckets(): List<TimeBucketDto> = client.timeBuckets(
        mapOf(
            "size" to "MONTH",
            "isArchived" to "false",
        ),
    )

    suspend fun monthAssets(bucket: String): List<AssetDto> = client.bucketAssets(
        mapOf(
            "size" to "MONTH",
            "timeBucket" to bucket,
            "isArchived" to "false",
        ),
    )

    suspend fun favoriteBuckets(): List<TimeBucketDto> = client.timeBuckets(
        mapOf(
            "size" to "MONTH",
            "isFavorite" to "true",
        ),
    )

    suspend fun favoriteMonthAssets(bucket: String): List<AssetDto> = client.bucketAssets(
        mapOf(
            "size" to "MONTH",
            "timeBucket" to bucket,
            "isFavorite" to "true",
        ),
    )

    suspend fun albums(): List<AlbumDto> = client.albums()

    suspend fun albumBuckets(albumId: String): List<TimeBucketDto> = client.timeBuckets(
        mapOf(
            "size" to "MONTH",
            "albumId" to albumId,
        ),
    )

    suspend fun albumMonthAssets(albumId: String, bucket: String): List<AssetDto> =
        client.bucketAssets(
            mapOf(
                "size" to "MONTH",
                "timeBucket" to bucket,
                "albumId" to albumId,
            ),
        )
}
