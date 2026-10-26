package app.lifeboat.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps big imports running when the screen is off or you switch apps. */
class WorkService : Service() {
    private val channel = "work"
    private var lastNotify = 0L
    private val listener: (Jobs.State) -> Unit = { s ->
        val now = System.currentTimeMillis()
        if (s.running && now - lastNotify > 1000) {
            lastNotify = now
            getSystemService(NotificationManager::class.java).notify(1, build(s))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val work = Jobs.takePending()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Vault work", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(this, 1, build(Jobs.state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (work == null) { stopSelf(); return START_NOT_STICKY }
        Jobs.listen(listener)
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifeboat:work")
        wl.acquire(6 * 60 * 60 * 1000L)
        Thread {
            var summary: String
            try {
                summary = work(Reporter())
            } catch (e: Throwable) {
                summary = "Stopped: ${e.message ?: e.javaClass.simpleName}"
            }
            try { Vault.get(this).flush() } catch (_: Exception) {}
            Jobs.update { it.copy(running = false, result = summary, current = "") }
            Jobs.unlisten(listener)
            if (wl.isHeld) wl.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notifyDone(summary)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun build(s: Jobs.State): Notification = NotificationCompat.Builder(this, channel)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle(s.title.ifEmpty { "Lifeboat" })
        .setContentText(if (s.pct) "${s.done / 10}% · ${s.added} found" else if (s.total > 0) "${s.done} of ${s.total}" else "Working…")
        .setProgress(s.total, s.done, s.total == 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun notifyDone(summary: String) {
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Lifeboat: done")
            .setContentText(summary.lineSequence().firstOrNull() ?: summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try { getSystemService(NotificationManager::class.java).notify(2, n) } catch (_: SecurityException) {}
    }

    companion object {
        fun ctx(c: Context) = c.applicationContext
    }
}
