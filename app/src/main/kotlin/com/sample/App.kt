package com.sample

import android.app.Application
import com.remotelog.RemoteLogcat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteLogcat.init(this)
    }
}
