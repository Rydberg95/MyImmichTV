package dev.myimmich.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Shared warm-amber palette (v0.6.0 redesign). Every screen pulls colors from
 * here so the TV app and the phone SPA (which mirrors these values in CSS
 * variables, see assets/web/style.css) stay in sync.
 */
object Palette {
    val Accent = Color(0xFFF5B15C)        // amber
    val AccentBright = Color(0xFFFFCF87)  // cursor/highlight text on dark
    val OnAccent = Color(0xFF221607)      // text on amber fills
    val Background = Color(0xFF0C0A08)    // warm near-black
    val Surface = Color(0xFF17130E)
    val Surface2 = Color(0xFF211B14)
    val Text = Color(0xFFF1EAE0)          // warm off-white
    val TextSoft = Color(0xFFC9BEAF)
    val Muted = Color(0xFFA79C8F)
    val MutedDim = Color(0xFF7E7468)
    val Border = Color(0xFF37302A)
    val BorderSubtle = Color(0xFF2A241E)
    val Error = Color(0xFFE8927C)
    val Warn = Color(0xFFF58B6C)          // cert-change warnings (kept more orange-red than Accent)
    val Success = Color(0xFF9CC08F)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    secondary = Palette.Surface2,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    error = Palette.Error,
)

@Composable
fun MyImmichTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = DarkColors.background,
                contentColor = DarkColors.onBackground,
                content = content,
            )
        },
    )
}
