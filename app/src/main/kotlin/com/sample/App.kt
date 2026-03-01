package com.sample

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugRemoteLogcat.init(this)
    }
}
