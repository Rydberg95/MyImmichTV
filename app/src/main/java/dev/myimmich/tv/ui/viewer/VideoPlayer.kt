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
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

private class RotatingVideoFrame(context: Context) : FrameLayout(context) {

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
) {
    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)

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
