package dev.myimmich.tv.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.remote.QrBitmap
import dev.myimmich.tv.remote.RemoteController
import dev.myimmich.tv.remote.RemoteServer
import dev.myimmich.tv.remote.SetupState
import dev.myimmich.tv.tls.TlsSupport
import dev.myimmich.tv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(
    initialUrl: String,
    initialKey: String,
    onSaved: () -> Unit,
    saveConfig: suspend (ServerConfig) -> Unit,
    remote: RemoteController,
    remoteServer: RemoteServer,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var apiKey by rememberSaveable { mutableStateOf(initialKey) }
    var manual by remember { mutableStateOf(false) }
    val setupState by remote.setupState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun fail(msg: String) {
        manual = true
    }

    fun validateAndSave(cert: TlsSupport.CertInfo?, caPins: List<String> = emptyList()) {
        scope.launch {
            try {
                val client = dev.myimmich.tv.api.ImmichClient(
                    http = TlsSupport.buildClient(
                        certFingerprint = cert?.fingerprint?.takeIf { it.isNotBlank() },
                        trustAny = false,
                        extraAccepted = caPins,
                    ),
                    serverUrl = url.trim().trimEnd('/'),
                    apiKey = apiKey.trim(),
                )
                val user = withContext(Dispatchers.IO) { client.me() }
                saveConfig(
                    ServerConfig(
                        serverUrl = url.trim().trimEnd('/'),
                        apiKey = apiKey.trim(),
                        certFingerprint = cert?.fingerprint.orEmpty(),
                        trustAny = false,
                        caPins = caPins,
                    )
                )
                onSaved()
            } catch (e: Exception) {
                manual = true
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up from your phone", style = MaterialTheme.typography.titleLarge, color = Palette.Accent)
            Text(
                "Scan with your phone camera",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Muted,
            )
            Box(
                modifier = Modifier
                    .background(Palette.Surface, RoundedCornerShape(20.dp))
                    .border(1.dp, Palette.Border, RoundedCornerShape(20.dp))
                    .padding(18.dp),
            ) {
                Image(
                    bitmap = QrBitmap.generate(remoteServer.pairingUrl()).asImageBitmap(),
                    contentDescription = "Setup QR code",
                    modifier = Modifier.size(380.dp),
                )
            }
            Text(
                remoteServer.pairingUrl(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Palette.TextSoft,
            )
            Text(
                "PIN " + remote.pin,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color = Palette.Accent,
            )
            SetupStatusLabel(setupState)
            OutlinedButton(onClick = { manual = !manual }) {
                Text(if (manual) "Hide manual setup" else "Set up with TV remote instead")
            }
        }
        Spacer(Modifier.width(80.dp))
        if (manual) {
            ManualSetupForm(
                url = url,
                apiKey = apiKey,
                onUrl = { url = it },
                onKey = { apiKey = it },
                onConnect = {
                    if (url.isBlank() || apiKey.isBlank()) {
                        fail("blank")
                    } else {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                TlsSupport.probe(url.trim().trimEnd('/') + "/api/server/ping")
                            }
                            when (result) {
                                is TlsSupport.ProbeResult.Trusted -> validateAndSave(null)
                                is TlsSupport.ProbeResult.Untrusted -> validateAndSave(
                                    result.cert,
                                    result.chain.drop(1)
                                        .flatMap { listOf(it.fingerprint, it.spkiFingerprint) }
                                        .distinct(),
                                )
                                is TlsSupport.ProbeResult.Error -> fail("unreachable")
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SetupStatusLabel(state: SetupState) {
    val text = when (state.phase) {
        "PROBING" -> "Contacting server…"
        "AWAITING_CONFIRM" -> "Check the fingerprint on your phone"
        "CONNECTING" -> "Validating API key…"
        "DONE" -> "Connected"
        "ERROR" -> "Error: " + (state.error ?: "unknown")
        else -> null
    }
    text?.let {
        val color = when (state.phase) {
            "DONE" -> Palette.Success
            "ERROR" -> Palette.Error
            else -> Palette.Muted
        }
        Text(it, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun ManualSetupForm(
    url: String,
    apiKey: String,
    onUrl: (String) -> Unit,
    onKey: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.4f)
            .background(Palette.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(20.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Manual setup", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text("Server URL") },
            placeholder = { Text("https://immich.your.home") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onKey,
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onConnect) { Text("Connect") }
        Text(
            "Fingerprint verification happens on first connect",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.MutedDim,
        )
    }
}
