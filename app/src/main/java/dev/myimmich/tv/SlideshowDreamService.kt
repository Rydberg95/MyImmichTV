package dev.myimmich.tv

import android.graphics.Color
import android.graphics.RenderEffect
import android.os.Build
import android.service.dreams.DreamService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.myimmich.tv.api.AssetDto
import dev.myimmich.tv.api.ImmichClient
import dev.myimmich.tv.api.ImmichThumb
import dev.myimmich.tv.api.ImmichThumbFetcher
import dev.myimmich.tv.api.TimeBucketDto
import dev.myimmich.tv.data.DreamSource
import dev.myimmich.tv.repo.LibraryRepository
import dev.myimmich.tv.tls.TlsSupport
import dev.myimmich.tv.ui.viewer.RotatingVideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.random.Random

/** One selected dream source with its own lazy month cursor. */
private class SourceFeed(
    val label: String,
    val buckets: List<TimeBucketDto>,
    val monthAssetsOf: suspend (String) -> List<AssetDto>,
) {
    var bucketIdx = 0
    var assets: List<AssetDto> = emptyList()
    var pos = 0

    /** Loads the next non-empty bucket (cycling); false when the source is exhausted. */
    suspend fun loadNextBucket(): Boolean {
        if (buckets.isEmpty()) return false
        val start = bucketIdx
        while (true) {
            val list = monthAssetsOf(buckets[bucketIdx].bucket).shuffled(Random.Default)
            bucketIdx = (bucketIdx + 1) % buckets.size
            if (list.isNotEmpty()) {
                assets = list
                pos = 0
                return true
            }
            if (bucketIdx == start) return false
        }
    }
}

/** Album buckets return local timestamps without offset ("2022-10-16T11:41:51.8");
 *  timeline/search include one. Handle both. */
internal fun formatDreamDate(raw: String): String? = runCatching {
    val parsed = OffsetDateTime.parse(raw)
    parsed.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy  ·  HH:mm"))
}.recoverCatching {
    LocalDateTime.parse(raw)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM yyyy  ·  HH:mm"))
}.getOrNull()

class SlideshowDreamService : DreamService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loader: ImageLoader? = null
    private var player: ExoPlayer? = null
    private var videoFrame: RotatingVideoFrame? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(false)
        setFullscreen(true)
        setScreenBright(false)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        fun blurredBackdrop() = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, android.graphics.Shader.TileMode.CLAMP))
            }
            alpha = 0f
        }
        fun fitForeground() = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
        }
        // two layers each: new image fades in on the back pair while the old fades out
        val bgViews = arrayOf(blurredBackdrop(), blurredBackdrop())
        val fgViews = arrayOf(fitForeground(), fitForeground())
        for (v in bgViews + fgViews) {
            root.addView(
                v,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)

        // optional per-slide info caption (date + place)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val infoView = TextView(this).apply {
            setTextColor(0xFFF1EAE0.toInt())
            textSize = 18f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(dp(40), dp(16), dp(40), dp(28))
            alpha = 0f
        }
        root.addView(
            infoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ),
        )

        val fadeMs = 900L
        var frontSlot = -1

        fun crossfadeTo(bitmap: android.graphics.Bitmap) {
            val next = if (frontSlot == -1) 0 else frontSlot xor 1
            val prev = frontSlot
            val bNext = bgViews[next]
            val fNext = fgViews[next]
            fNext.animate().cancel()
            bNext.animate().cancel()
            fNext.setImageBitmap(bitmap)
            bNext.setImageBitmap(bitmap)
            fNext.alpha = 0f
            fNext.animate().alpha(1f).setDuration(fadeMs).start()
            // backdrop always on: fills the gaps for any photo narrower than the
            // screen (portrait or near-16:9 landscape) with a blur instead of bars;
            // fully-covering photos just hide it
            bNext.alpha = 0f
            bNext.animate().alpha(1f).setDuration(fadeMs).start()
            if (prev != -1) {
                val bPrev = bgViews[prev]
                val fPrev = fgViews[prev]
                fPrev.animate().alpha(0f).setDuration(fadeMs)
                    .withEndAction { fPrev.setImageDrawable(null) }.start()
                bPrev.animate().alpha(0f).setDuration(fadeMs)
                    .withEndAction { bPrev.setImageDrawable(null) }.start()
            }
            frontSlot = next
        }

        fun hideVideo() {
            videoFrame?.visibility = View.GONE
        }

        var dreamShowInfo = false

        fun showInfo(asset: AssetDto, detail: dev.myimmich.tv.api.AssetDetailDto?) {
            infoView.animate().cancel()
            if (!dreamShowInfo) {
                infoView.alpha = 0f
                return
            }
            // localDateTime = wall clock in the photo's own timezone; bucket dates are UTC
            val date = detail?.localDateTime?.let { dev.myimmich.tv.api.parseWallClock(it) }
                ?.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy  ·  HH:mm"))
                ?: asset.fileCreatedAt?.let { formatDreamDate(it) }
            val exif = detail?.exifInfo
            val place = listOfNotNull(exif?.city ?: asset.city, exif?.country ?: asset.country)
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
            val text = listOfNotNull(date, place).joinToString("\n")
            if (text.isBlank()) {
                infoView.alpha = 0f
                return
            }
            infoView.text = text
            infoView.alpha = 0f
            infoView.animate().alpha(0.95f).setDuration(fadeMs).start()
        }

        fun hideInfo() {
            infoView.animate().cancel()
            infoView.animate().alpha(0f).setDuration(fadeMs / 2).start()
        }

        suspend fun dreamLoop() {
            val app = application as MyImmichApp
            val config = app.settings.serverConfig.first() ?: run { finish(); return }
            val seconds = app.settings.dreamSeconds.first().coerceAtLeast(5)
            val includeVideos = app.settings.dreamIncludeVideos.first()
            dreamShowInfo = app.settings.dreamShowInfo.first()

            val http = TlsSupport.buildClient(
                certFingerprint = config.certFingerprint.takeIf { it.isNotBlank() },
                trustAny = config.trustAny,
                extraAccepted = config.caPins,
            )
            val authedHttp = http.newBuilder()
                .addInterceptor { chain ->
                    val r = chain.request().newBuilder()
                        .header("x-api-key", config.apiKey)
                        .build()
                    chain.proceed(r)
                }
                .build()
            val client = ImmichClient(http, config.serverUrl, config.apiKey)
            val repo = LibraryRepository(client)
            val imageLoader = ImageLoader.Builder(this@SlideshowDreamService)
                .components { add(ImmichThumbFetcher.Factory(authedHttp)) }
                .build()
            loader = imageLoader

            val sources = app.settings.dreamSources.first().ifEmpty { listOf(DreamSource("timeline")) }
            val feeds = mutableListOf<SourceFeed>()
            for (s in sources) {
                when (s.kind) {
                    "favorites" -> feeds += SourceFeed(
                        "favorites",
                        repo.favoriteBuckets().sortedByDescending { it.bucket },
                    ) { repo.favoriteMonthAssets(it) }
                    "album" -> {
                        val id = s.id ?: continue
                        feeds += SourceFeed(
                            s.name ?: "album",
                            repo.albumBuckets(id).sortedByDescending { it.bucket },
                        ) { repo.albumMonthAssets(id, it) }
                    }
                    else -> feeds += SourceFeed(
                        "timeline",
                        repo.timelineBuckets().sortedByDescending { it.bucket },
                    ) { repo.monthAssets(it) }
                }
            }
            Log.d(
                "ImmichTV",
                "Dream sources: ${feeds.joinToString { f -> "${f.label} (${f.buckets.size} buckets)" }}" +
                    ", interval=${seconds}s videos=$includeVideos info=$dreamShowInfo",
            )
            if (feeds.isEmpty()) return

            // round-robin between sources so albums interleave
            suspend fun nextAsset(): AssetDto? {
                while (feeds.isNotEmpty()) {
                    val f = feeds.removeAt(0)
                    if (f.pos >= f.assets.size && !f.loadNextBucket()) continue // exhausted; drop
                    val a = f.assets.getOrNull(f.pos) ?: continue
                    f.pos++
                    feeds.add(f)
                    return a
                }
                return null
            }

            var consecutiveFailures = 0
            while (currentCoroutineContext().isActive) {
                val asset = nextAsset() ?: break
                if (asset.isVideo && !includeVideos) continue

                if (asset.isVideo) {
                    hideInfo()
                    val ended = playVideo(client.originalUrl(asset.id), authedHttp, asset.durationMs, root)
                    if (ended) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        delay(4000)
                        if (consecutiveFailures >= 10) break
                    }
                    hideVideo()
                    continue
                }

                // fetch the photo-local timestamp (and exact place) in parallel with the image
                val detail = if (dreamShowInfo) {
                    withContext(Dispatchers.IO) {
                        runCatching { client.assetDetail(asset.id) }.getOrNull()
                    }
                } else {
                    null
                }

                val result = imageLoader.execute(
                    ImageRequest.Builder(this@SlideshowDreamService)
                        .data(ImmichThumb(client.thumbnailUrl(asset.id)))
                        .memoryCacheKey("preview-" + asset.id)
                        .build()
                )
                if (result is SuccessResult) {
                    consecutiveFailures = 0
                    crossfadeTo(result.image.toBitmap())
                    showInfo(asset, detail)
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 10) break
                }
                delay(seconds * 1000L)
            }
        }

        serviceScope.launch {
            // An uncaught exception here would kill the whole app process (the dream dies with
            // a black flash and takes the remote server down with it). Retry transient failures,
            // then end the dream cleanly instead of crashing.
            var attempt = 0
            while (isActive) {
                try {
                    dreamLoop()
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    Log.w("ImmichTV", "Dream load failed (attempt $attempt): ${e.message}")
                    if (attempt >= 3 || !isActive) {
                        finish()
                        break
                    }
                    delay(10_000)
                }
            }
        }
    }

    /** Plays a video full-window; returns true if it reached STATE_ENDED. */
    private suspend fun playVideo(
        url: String,
        authedHttp: okhttp3.OkHttpClient,
        durationMs: Long?,
        root: FrameLayout,
    ): Boolean {
        try {
            if (player == null) {
                player = ExoPlayer.Builder(this)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(authedHttp)))
                    .build()
                    .also { p ->
                        p.addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                videoFrame?.update(videoSize)
                            }
                        })
                    }
                videoFrame = RotatingVideoFrame(this).apply { visibility = View.GONE }
                root.addView(
                    videoFrame,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                player?.setVideoTextureView(videoFrame!!.textureView())
            }
            val p = player ?: return false
            val vf = videoFrame ?: return false
            vf.visibility = View.VISIBLE
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
            // safety cap: declared duration + 30 s headroom (10 min when unknown)
            val cap = (durationMs ?: 600_000L) + 30_000L
            val ended = withTimeoutOrNull(cap) { awaitPlaybackEnd(p) } ?: false
            p.stop()
            p.clearMediaItems()
            return ended
        } catch (e: Exception) {
            Log.w("ImmichTV", "Dream video failed: ${e.message}")
            player?.stop()
            return false
        }
    }

    /** Resumes with true on STATE_ENDED, false on error. */
    private suspend fun awaitPlaybackEnd(p: Player): Boolean =        suspendCancellableCoroutine { cont ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finish(true)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w("ImmichTV", "Dream video error: ${error.errorCodeName}")
                    finish(false)
                }

                fun finish(ended: Boolean) {
                    p.removeListener(this)
                    if (cont.isActive) cont.resume(ended)
                }
            }
            p.addListener(listener)
            cont.invokeOnCancellation { p.removeListener(listener) }
        }

    override fun onDetachedFromWindow() {
        serviceScope.cancel()
        player?.release()
        player = null
        videoFrame = null
        loader?.shutdown()
        loader = null
        super.onDetachedFromWindow()
    }
}
