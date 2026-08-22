package dev.myimmich.tv.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.myimmich.tv.remote.QrBitmap
import dev.myimmich.tv.remote.RemoteServer

@Composable
fun PairingScreen(server: RemoteServer, pin: String) {
    val bitmap = QrBitmap.generate(server.pairingUrl())
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D10))
            .padding(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing QR code",
            modifier = Modifier.size(420.dp),
        )
        Spacer(Modifier.width(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phone remote", style = MaterialTheme.typography.displaySmall, color = Color(0xFF80DEEA))
            Text("Scan with your phone camera", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(8.dp))
            Text("or open", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0BEC5))
            Text(
                server.pairingUrl(),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFECEFF1),
            )
            Text("and enter PIN", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0BEC5))
            Text(
                pin,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF80DEEA),
            )
        }
    }
}
