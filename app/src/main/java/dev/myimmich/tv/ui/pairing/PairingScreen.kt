package dev.myimmich.tv.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.myimmich.tv.remote.QrBitmap
import dev.myimmich.tv.remote.RemoteServer
import dev.myimmich.tv.ui.theme.Palette

@Composable
fun PairingScreen(server: RemoteServer, pin: String) {
    val bitmap = QrBitmap.generate(server.pairingUrl())
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(Palette.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Pairing QR code",
                modifier = Modifier.size(420.dp),
            )
        }
        Spacer(Modifier.width(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phone remote", style = MaterialTheme.typography.displaySmall, color = Palette.Accent)
            Text("Scan with your phone camera", style = MaterialTheme.typography.bodyLarge, color = Palette.TextSoft)
            Spacer(Modifier.size(8.dp))
            Text("or open", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Text(
                server.pairingUrl(),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = Palette.TextSoft,
            )
            Text("and enter PIN", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Text(
                pin,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                color = Palette.Accent,
            )
        }
    }
}
