package dev.lunartear.host

import android.app.Application
import dev.lunartear.host.core.LunarHost

class LunarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LunarHost.initialize(this)
    }
}
