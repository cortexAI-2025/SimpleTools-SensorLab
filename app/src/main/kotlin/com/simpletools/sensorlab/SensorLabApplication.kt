package com.simpletools.sensorlab

import android.app.Application

class SensorLabApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
