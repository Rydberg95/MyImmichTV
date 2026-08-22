package dev.myimmich.tv.ui.viewer

import android.content.Context
import android.graphics.Color
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale

/** TextureView frame with manual aspect-fit + rotation; shared by viewer and screensaver
 *  (SurfaceView/PlayerView renders black on the Amlogic box — see README). */
class RotatingVideoFrame(context: Context) : FrameLayout(context) {

    private val texture: TextureView = TextureView(context)
    private var videoW = 0
    private var videoH = 0
    private var rotationDeg = 0
    private var pixelRatio = 1f

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture)
    }

    fun textureView(): TextureView = texture

    fun update(size: VideoSize) {
        videoW = size.width
        videoH = size.height
        rotationDeg = size.unappliedRotationDegrees
        pixelRatio = size.pixelWidthHeightRatio
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (videoW == 0 || videoH == 0 || width == 0 || height == 0) {
            texture.layout(0, 0, width, height)
            return
        }
        val bufferAspect = (videoW * pixelRatio) / videoH.toFloat()
        val rotated = rotationDeg == 90 || rotationDeg == 270
        val displayAspect = if (rotated) 1f / bufferAspect else bufferAspect

        var vw = width.toFloat()
        var vh = vw / displayAspect
        if (vh > height) {
            vh = height.toFloat()
            vw = vh * displayAspect
        }

        val cx = width / 2f
        val cy = height / 2f
        if (rotated) {
            texture.rotation = rotationDeg.toFloat()
            val lw = vh
            val lh = vw
            texture.layout(
                (cx - lw / 2).toInt(),
                (cy - lh / 2).toInt(),
                (cx + lw / 2).toInt(),
                (cy + lh / 2).toInt(),
            )
        } else {
            texture.rotation = if (rotationDeg == 180) 180f else 0f
            texture.layout(
                (cx - vw / 2).toInt(),
                (cy - vh / 2).toInt(),
                (cx + vw / 2).toInt(),
                (cy + vh / 2).toInt(),
            )
        }
    }
}

@Composable
fun VideoPlayer(
    url: String,
    httpClient: okhttp3.OkHttpClient,
    modifier: Modifier = Modifier,
    toggleTick: Int = 0,
    onEnded: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnError by rememberUpdatedState(onError)

    val frame = remember { RotatingVideoFrame(context) }

    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(httpClient))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
    }

    LaunchedEffect(player, frame) {
        player.setVideoTextureView(frame.textureView())
    }

    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(toggleTick) {
        if (toggleTick > 0) player.playWhenReady = !player.playWhenReady
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentOnEnded()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                frame.update(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnError(describePlaybackError(error))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { frame },
        modifier = modifier,
    )
}

private fun describePlaybackError(error: PlaybackException): String {
    val chain = generateSequence<Throwable>(error) { it.cause }
    val decoderInit =
        chain.filterIsInstance<DecoderInitializationException>().firstOrNull()
    val format = (error as? ExoPlaybackException)?.rendererFormat
    if (decoderInit != null) {
        val mime = format?.sampleMimeType ?: decoderInit.mimeType
        val title = if (mime?.startsWith("audio") == true) {
            "Audio track cannot be decoded by this TV"
        } else {
            "Video format not supported by this TV"
        }
        val dims = buildString {
            if (format != null && format.width > 0 && format.height > 0) {
                append("${format.width} × ${format.height}")
                if (format.frameRate > 0f) {
                    append(" @ ").append(formatFps(format.frameRate)).append(" fps")
                }
            }
        }
        return listOfNotNull(title, friendlyCodec(format?.codecs ?: mime), dims.ifBlank { null })
            .joinToString("\n")
    }
    val reason = chain.lastOrNull()?.message ?: error.errorCodeName
    return "Playback failed\n$reason"
}

private fun formatFps(fps: Float): String =
    if (fps == fps.toInt().toFloat()) "${fps.toInt()}" else String.format(Locale.US, "%.1f", fps)

private fun friendlyCodec(codecs: String?): String {
    if (codecs.isNullOrBlank()) return "Unknown codec"
    val parts = codecs.split(".")
    val base = parts.first()
    val name = when (base) {
        "avc1", "avc3" -> "H.264 (AVC)"
        "hev1", "hvc1" -> "H.265 (HEVC)"
        "av01" -> "AV1"
        "vp09", "vp9" -> "VP9"
        "vp08", "vp8" -> "VP8"
        "mp4a" -> "AAC"
        else -> base
    }
    var detail = ""
    if ((base == "avc1" || base == "avc3") && parts.size > 1) {
        val pli = parts[1]
        if (pli.length == 6 && pli.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val profile = pli.substring(0, 2).toInt(16)
            val level = pli.substring(4, 6).toInt(16)
            val profileName = when (profile) {
                0x42 -> "Baseline"
                0x4D -> "Main"
                0x58 -> "Extended"
                0x64 -> "High"
                0x6E -> "High 10"
                0x7A -> "High 4:2:2"
                0xF4 -> "High 4:4:4"
                else -> null
            }
            if (profileName != null) detail = " $profileName Profile, Level ${level / 10}.${level % 10}"
        }
    }
    return name + detail + " [$codecs]"
}
