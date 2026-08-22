package dev.myimmich.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80DEEA),
    onPrimary = Color.Black,
    secondary = Color(0xFF455A64),
    background = Color(0xFF050708),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFECEFF1),
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
