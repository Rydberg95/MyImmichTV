package dev.myimmich.tv

import android.app.Application
import dev.myimmich.tv.data.AppSettings
import dev.myimmich.tv.remote.RemoteController

class MyImmichApp : Application() {
    lateinit var settings: AppSettings
        private set

    val remote = RemoteController()

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }
}
