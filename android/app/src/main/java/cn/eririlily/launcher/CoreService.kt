package cn.eririlily.launcher

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the launcher alive while a download or install runs in the
 * background (the system would otherwise stop the core with the app).
 */
class CoreService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra("label")
        if (label == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, ELApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(label)
            .setContentText(intent.getStringExtra("detail") ?: "樱梨启动器正在后台运行")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                val p = intent.getIntExtra("progress", -1)
                if (p >= 0) setProgress(100, p, false) else setProgress(0, 0, true)
            }
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        return START_NOT_STICKY
    }

    companion object {
        fun update(ctx: Context, label: String?, detail: String? = null, progress: Int = -1) {
            val i = Intent(ctx, CoreService::class.java)
            if (label != null) {
                i.putExtra("label", label).putExtra("detail", detail).putExtra("progress", progress)
                runCatching { ctx.startForegroundService(i) }
            } else {
                runCatching { ctx.startService(i) }
            }
        }
    }
}
