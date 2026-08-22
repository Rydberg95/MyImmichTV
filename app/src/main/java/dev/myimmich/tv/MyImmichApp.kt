package dev.myimmich.tv

import android.app.Application
import dev.myimmich.tv.data.AppSettings
import dev.myimmich.tv.remote.RemoteController
import dev.myimmich.tv.remote.RemoteServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyImmichApp : Application() {

    lateinit var settings: AppSettings
        private set

    val remote = RemoteController()
    lateinit var remoteServer: RemoteServer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        remoteServer = RemoteServer(this, remote, settings)
        remoteServer.start()
        appScope.launch {
            settings.serverConfig.collect { remoteServer.setConfig(it) }
        }
    }
}
