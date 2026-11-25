package com.example.app

import android.app.Application

class PcbInspectorApplication : Application() {
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.runStartupMaintenance()
    }
}
