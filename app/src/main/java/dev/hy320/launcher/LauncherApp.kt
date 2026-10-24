package dev.hy320.launcher

import android.app.Application

/** Ponto de partida do processo (launcher ou filtro): liga o guarda do filtro. */
class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FilterGuard.start(this)
    }
}
