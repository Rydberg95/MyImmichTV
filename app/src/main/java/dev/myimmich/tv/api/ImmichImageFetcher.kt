package dev.myimmich.tv.api

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.FileSystem

data class ImmichThumb(val url: String)

class ImmichThumbFetcher(
    private val http: OkHttpClient,
    private val data: ImmichThumb,
    @Suppress("unused") private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val response = http.newCall(Request.Builder().url(data.url).build()).execute()
        try {
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            SourceFetchResult(
                source = coil3.decode.ImageSource(Buffer().apply { write(bytes) }, FileSystem.SYSTEM),
                mimeType = response.header("Content-Type")?.substringBefore(';'),
                dataSource = DataSource.NETWORK,
            )
        } finally {
            response.close()
        }
    }

    class Factory(private val http: OkHttpClient) : Fetcher.Factory<ImmichThumb> {
        override fun create(data: ImmichThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ImmichThumbFetcher(http, data, options)
    }
}
