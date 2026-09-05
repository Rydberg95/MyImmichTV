package dev.myimmich.tv.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ImmichClient(
    private val http: OkHttpClient,
    private val serverUrl: String,
    private val apiKey: String,
) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    init {
        require(serverUrl.startsWith("http")) { "server URL must start with http(s)" }
    }

    private fun req(path: String, query: Map<String, String> = emptyMap()): Request {
        val base = serverUrl.toHttpUrlOrNull() ?: throw IOException("bad server url: $serverUrl")
        val urlBuilder = base.newBuilder().apply {
            val segments = path.trim('/').split('/')
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }

    private suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            http.newCall(req(path, query)).await().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $path: ${body.take(300)}")
                body
            }
        }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        http.newCall(req("api/server/ping")).await().use { it.isSuccessful }
    }

    suspend fun me(): UserDto =
        json.decodeFromString<UserDto>(getJson("api/users/me"))

    suspend fun timeBuckets(query: Map<String, String>): List<TimeBucketDto> =
        json.decodeFromString(getJson("api/timeline/buckets", query))

    suspend fun bucketAssets(query: Map<String, String>): List<AssetDto> {
        val columns = json.decodeFromString<TimelineBucketColumnsDto>(
            getJson("api/timeline/bucket", query + mapOf("withPartners" to "false"))
        )
        return AssetDto.fromColumns(columns)
    }



    suspend fun albums(): List<AlbumDto> =
        json.decodeFromString(getJson("api/albums"))

    suspend fun smartSearch(query: String, limit: Int = 60): List<AssetDto> = run {
        val body = json.encodeToString(
            SearchRequestDto.serializer(),
            SearchRequestDto(query = query, limit = limit),
        )
        val response = withContext(Dispatchers.IO) {
            http.newCall(
                Request.Builder()
                    .url("$serverUrl/api/search/smart")
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).await()
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(200)}")
            val parsed = json.decodeFromString<SmartSearchResponseDto>(text)
            parsed.assets.items.map { item ->
                AssetDto(
                    id = item.id,
                    type = item.type,
                    fileCreatedAt = item.fileCreatedAt,
                    isFavorite = item.isFavorite,
                    durationMs = item.duration?.let { d ->
                        val prim = d as? kotlinx.serialization.json.JsonPrimitive
                        when {
                            prim == null -> null
                            prim.isString -> runCatching {
                                val parts = prim.content.split(":")
                                val h = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                                val m = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                val sec = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                                ((h * 3600 + m * 60 + sec) * 1000).toLong()
                            }.getOrNull()
                            else -> prim.longOrNull
                        }
                    },
                    livePhotoVideoId = item.livePhotoVideoId,
                    thumbhash = item.thumbhash,
                    city = item.exifInfo?.city,
                    country = item.exifInfo?.country,
                )
            }
        }
    }

    /** Named people across all /api/people pages (server pages at 500 and ignores filters). */
    suspend fun people(): List<PersonDto> {
        val all = mutableListOf<PersonDto>()
        var page = 1
        while (true) {
            val resp = json.decodeFromString<PeopleResponseDto>(getJson("api/people", mapOf("page" to page.toString())))
            all += resp.people
            if (!resp.hasNextPage || resp.people.isEmpty()) break
            page++
            if (page > 50) break // safety bound
        }
        return all
    }

    suspend fun assetDetail(assetId: String): AssetDetailDto =
        json.decodeFromString<AssetDetailDto>(getJson("api/assets/$assetId"))

    fun thumbnailUrl(assetId: String): String = "$serverUrl/api/assets/$assetId/thumbnail?size=preview"
    fun smallThumbUrl(assetId: String): String = "$serverUrl/api/assets/$assetId/thumbnail?size=thumbnail"
    fun originalUrl(assetId: String): String = "$serverUrl/api/assets/$assetId/original"
    fun personFaceUrl(personId: String): String = "$serverUrl/api/people/$personId/thumbnail"
}
