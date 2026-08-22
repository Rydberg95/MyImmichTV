package dev.myimmich.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.myimmich.tv.data.ServerConfig
import dev.myimmich.tv.ui.setup.SetupScreen
import dev.myimmich.tv.ui.theme.MyImmichTheme
import dev.myimmich.tv.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.map

sealed interface ConfigState {
    data object Loading : ConfigState
    data object Missing : ConfigState
    data class Ready(val config: ServerConfig) : ConfigState
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MyImmichApp
        val stateFlow = app.settings.serverConfig.map { c ->
            if (c == null) ConfigState.Missing else ConfigState.Ready(c)
        }
        setContent {
            MyImmichTheme {
                Root(stateFlow)
            }
        }
    }
}

@Composable
private fun Root(flow: kotlinx.coroutines.flow.Flow<ConfigState>) {
    val state by flow.collectAsStateWithLifecycle(initialValue = ConfigState.Loading)
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as MyImmichApp
    when (val s = state) {
        ConfigState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black))
        ConfigState.Missing -> SetupScreen(
            initialUrl = "",
            initialKey = "",
            onSaved = {},
            saveConfig = app.settings::saveServer,
            remote = app.remote,
            remoteServer = app.remoteServer,
        )
        is ConfigState.Ready -> ViewerScreen(s.config, app.settings, app.remote, app.remoteServer)
    }
}
