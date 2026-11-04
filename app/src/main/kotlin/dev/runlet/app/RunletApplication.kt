package dev.runlet.app

import android.app.Application
import dev.runlet.core.platform.RunletServices

class RunletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RunletServices.init(this, R.drawable.ic_notify)
    }
}
