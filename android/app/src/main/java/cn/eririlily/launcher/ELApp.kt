package cn.eririlily.launcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File

class ELApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)!!
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Launcher data: config, accounts, runtimes and .minecraft. */
    val launcherDir: File get() = File(filesDir, "EririLily").apply { mkdirs() }

    companion object {
        const val CHANNEL = "tasks"
        lateinit var instance: ELApp
            private set
    }
}
