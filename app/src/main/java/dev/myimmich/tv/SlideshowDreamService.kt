package dev.myimmich.tv

import android.graphics.Color
import android.graphics.RenderEffect
import android.os.Build
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.myimmich.tv.api.ImmichClient
import dev.myimmich.tv.api.ImmichThumb
import dev.myimmich.tv.api.ImmichThumbFetcher
import dev.myimmich.tv.repo.LibraryRepository
import dev.myimmich.tv.tls.TlsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class SlideshowDreamService : DreamService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loader: ImageLoader? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(false)
        setFullscreen(true)
        setScreenBright(false)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, android.graphics.Shader.TileMode.CLAMP))
            }
        }
        val fg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(
            bg,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            fg,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        setContentView(root)

        serviceScope.launch {
            val app = application as MyImmichApp
            val config = app.settings.serverConfig.first() ?: run { finish(); return@launch }
            val seconds = app.settings.slideshowSeconds.first().coerceAtLeast(5)

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

            val buckets = repo.timelineBuckets().sortedByDescending { it.bucket }
            if (buckets.isEmpty()) return@launch
            var assets = repo.monthAssets(buckets.first().bucket).shuffled(Random.Default)
            if (assets.isEmpty()) return@launch

            var position = 0
            while (isActive) {
                if (position >= assets.size) {
                    val nextBucket = buckets.getOrNull(1)
                    assets = (if (nextBucket != null) repo.monthAssets(nextBucket.bucket) else assets)
                        .shuffled(Random.Default)
                    position = 0
                }
                val asset = assets.getOrNull(position) ?: break
                position++
                if (asset.isVideo) continue

                val result = imageLoader.execute(
                    ImageRequest.Builder(this@SlideshowDreamService)
                        .data(ImmichThumb(client.thumbnailUrl(asset.id)))
                        .memoryCacheKey("preview-" + asset.id)
                        .build()
                )
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    fg.setImageBitmap(bitmap)
                    if (bitmap.width < bitmap.height) {
                        bg.setImageBitmap(bitmap)
                        bg.alpha = 1f
                    } else {
                        bg.alpha = 0f
                    }
                }
                delay(seconds * 1000L)
            }
        }
    }

    override fun onDetachedFromWindow() {
        serviceScope.cancel()
        loader?.shutdown()
        loader = null
        super.onDetachedFromWindow()
    }
}
