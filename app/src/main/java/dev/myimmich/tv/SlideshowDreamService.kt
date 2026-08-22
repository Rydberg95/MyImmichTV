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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.random.Random

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

        val fadeMs = 900L
        var frontSlot = -1

        fun crossfadeTo(bitmap: android.graphics.Bitmap) {
            val next = if (frontSlot == -1) 0 else frontSlot xor 1
            val prev = frontSlot
            val portrait = bitmap.width < bitmap.height
            val bNext = bgViews[next]
            val fNext = fgViews[next]
            fNext.animate().cancel()
            bNext.animate().cancel()
            fNext.setImageBitmap(bitmap)
            bNext.setImageBitmap(bitmap)
            fNext.alpha = 0f
            fNext.animate().alpha(1f).setDuration(fadeMs).start()
            if (portrait) {
                bNext.alpha = 0f
                bNext.animate().alpha(1f).setDuration(fadeMs).start()
            } else {
                bNext.alpha = 0f
            }
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

        serviceScope.launch {
            val app = application as MyImmichApp
            val config = app.settings.serverConfig.first() ?: run { finish(); return@launch }
            val seconds = app.settings.dreamSeconds.first().coerceAtLeast(5)
            val includeVideos = app.settings.dreamIncludeVideos.first()

            val http = TlsSupport.buildClient(
                certFingerprint = config.certFingerprint.takeIf { it.isNotBlank() },
                trustAny = config.trustAny,
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

            val buckets: List<TimeBucketDto>
            val monthAssetsOf: suspend (String) -> List<AssetDto>
            val sourceId = app.settings.dreamSourceId.first()
            val sourceName = app.settings.dreamSourceName.first()
            when (sourceId) {
                "favorites" -> {
                    buckets = repo.favoriteBuckets().sortedByDescending { it.bucket }
                    monthAssetsOf = { repo.favoriteMonthAssets(it) }
                }
                "" -> {
                    buckets = repo.timelineBuckets().sortedByDescending { it.bucket }
                    monthAssetsOf = { repo.monthAssets(it) }
                }
                else -> {
                    buckets = repo.albumBuckets(sourceId).sortedByDescending { it.bucket }
                    monthAssetsOf = { repo.albumMonthAssets(sourceId, it) }
                }
            }
            Log.d(
                "ImmichTV",
                "Dream source: ${sourceId.ifBlank { "timeline" }} (${sourceName.ifBlank { "latest months" }}), " +
                    "${buckets.size} buckets, interval=${seconds}s videos=$includeVideos",
            )
            if (buckets.isEmpty()) return@launch

            var bucketIndex = 0
            var assets: List<AssetDto> = emptyList()
            var position = 0
            var consecutiveFailures = 0
            while (isActive) {
                if (position >= assets.size) {
                    // cycle through buckets (newest first) until one yields assets
                    val start = bucketIndex
                    var next: List<AssetDto> = emptyList()
                    while (true) {
                        next = monthAssetsOf(buckets[bucketIndex].bucket).shuffled(Random.Default)
                        if (next.isNotEmpty()) break
                        bucketIndex = (bucketIndex + 1) % buckets.size
                        if (bucketIndex == start) break
                    }
                    if (next.isEmpty()) break
                    assets = next
                    position = 0
                }
                val asset = assets.getOrNull(position) ?: break
                position++
                if (asset.isVideo && !includeVideos) continue

                if (asset.isVideo) {
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

                val result = imageLoader.execute(
                    ImageRequest.Builder(this@SlideshowDreamService)
                        .data(ImmichThumb(client.thumbnailUrl(asset.id)))
                        .memoryCacheKey("preview-" + asset.id)
                        .build()
                )
                if (result is SuccessResult) {
                    consecutiveFailures = 0
                    crossfadeTo(result.image.toBitmap())
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 10) break
                }
                delay(seconds * 1000L)
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
    private suspend fun awaitPlaybackEnd(p: Player): Boolean =
        suspendCancellableCoroutine { cont ->
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
