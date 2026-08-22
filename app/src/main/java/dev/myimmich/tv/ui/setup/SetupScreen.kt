package dev.myimmich.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.tls.TlsSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SetupPhase { Idle, Probing, AwaitPin, Connecting, Done, Failed }

@Composable
fun SetupScreen(
    initialUrl: String,
    initialKey: String,
    onSaved: () -> Unit,
    saveConfig: suspend (ServerConfig) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var apiKey by rememberSaveable { mutableStateOf(initialKey) }
    var phase by remember { mutableStateOf(SetupPhase.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<TlsSupport.CertInfo?>(null) }
    val scope = rememberCoroutineScope()

    fun fail(msg: String) {
        phase = SetupPhase.Failed
        message = msg
    }

    fun validateAndSave(cert: TlsSupport.CertInfo?) {
        phase = SetupPhase.Connecting
        message = null
        scope.launch {
            try {
                val client = dev.myimmich.tv.api.ImmichClient(
                    http = TlsSupport.buildClient(
                        certFingerprint = cert?.fingerprint?.takeIf { it.isNotBlank() },
                        trustAny = false,
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
                    )
                )
                phase = SetupPhase.Done
                message = "Connected as ${user.name ?: user.email ?: user.id}"
                onSaved()
            } catch (e: Exception) {
                fail("Connection failed: ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D10))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 160.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("My Immich TV", style = MaterialTheme.typography.displaySmall, color = Color(0xFF80DEEA))
        Text("Connect to your Immich server", style = MaterialTheme.typography.bodyLarge)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://immich.your.home") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            placeholder = { Text("Immich > Account > API Keys") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (url.isBlank() || apiKey.isBlank()) {
                    fail("Server URL and API key are required")
                    return@Button
                }
                phase = SetupPhase.Probing
                message = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        TlsSupport.probe(url.trim().trimEnd('/') + "/api/server/ping")
                    }
                    when (result) {
                        is TlsSupport.ProbeResult.Trusted -> validateAndSave(null)
                        is TlsSupport.ProbeResult.Untrusted -> {
                            pending = result.cert
                            phase = SetupPhase.AwaitPin
                        }
                        is TlsSupport.ProbeResult.Error -> fail("Cannot reach server: ${result.message}")
                    }
                }
            },
            enabled = phase != SetupPhase.Probing && phase != SetupPhase.Connecting,
        ) {
            Text(if (phase == SetupPhase.Probing || phase == SetupPhase.Connecting) "Working…" else "Connect")
        }

        when (phase) {
            SetupPhase.Probing, SetupPhase.Connecting -> CircularProgressIndicator()
            SetupPhase.AwaitPin -> {
                val cert = pending
                if (cert != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C262E))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Self-signed certificate detected.", style = MaterialTheme.typography.titleMedium)
                        Text("Verify this SHA-256 fingerprint against your reverse proxy certificate:")
                        Text(
                            cert.fingerprint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF80DEEA),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { validateAndSave(cert) }) { Text("It matches — trust it") }
                            OutlinedButton(onClick = {
                                phase = SetupPhase.Idle
                                pending = null
                            }) { Text("Cancel") }
                        }
                    }
                }
            }
            else -> {}
        }

        message?.let {
            val color = if (phase == SetupPhase.Failed) Color(0xFFEF9A9A) else Color(0xFFA5D6A7)
            Text(it, color = color)
        }

        Spacer(Modifier.width(1.dp))
    }
}
