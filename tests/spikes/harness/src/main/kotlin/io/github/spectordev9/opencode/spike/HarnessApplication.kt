package io.github.spectordev9.opencode.spike

import android.app.Application

class HarnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeReport.initialize(this)
        HarnessProbeRunner.initialize(this)
    }
}
