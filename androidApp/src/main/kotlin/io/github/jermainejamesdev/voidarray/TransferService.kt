package io.github.jermainejamesdev.voidarray

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jermainejamesdev.voidarray.core.TransferDirection
import io.github.jermainejamesdev.voidarray.core.TransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while files move. Started when a transfer becomes active and stopped as
 * soon as none are, because dataSync services share a 6-hour-per-day budget across the app.
 */
class TransferService : Service() {
    private val scope = MainScope()
    private var observer: Job? = null
    private var lastNotifiedAt = 0L

    private val engine get() = (application as VoidArrayApplication).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Transfers")
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(engine.transfers.value), type)
        if (observer == null) {
            observer = scope.launch {
                engine.transfers.collect { transfers ->
                    // Notification updates are rate-limited by the system; more than about one a second gets dropped.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifiedAt >= 1_000) {
                        lastNotifiedAt = now
                        runCatching {
                            NotificationManagerCompat.from(this@TransferService)
                                .notify(NOTIFICATION_ID, buildNotification(transfers))
                        }
                    }
                }
            }
        }
        // If the process dies mid-transfer the sockets are gone too; restarting the service would show a stale notification.
        return START_NOT_STICKY
    }

    /**
     * Called when the dataSync budget is exhausted. The service must stop within seconds or the system
     * crashes the app, so transfers are ended as resumable and the service stops itself.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.interruptActiveTransfers(
            "Android's daily background transfer limit was reached. Open VoidArray and send again to resume.",
        )
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(transfers: List<TransferState>): Notification {
        val active = transfers.filter { it.isActive }
        val total = active.sumOf { it.totalBytes }
        val done = active.sumOf { it.bytesTransferred }
        val receiving = active.any { it.direction == TransferDirection.RECEIVE }
        val title = when {
            active.isEmpty() -> "Finishing up"
            active.size == 1 && receiving -> "Receiving from ${active.first().peerAlias}"
            active.size == 1 -> "Sending to ${active.first().peerAlias}"
            else -> "${active.size} transfers in progress"
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (receiving) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(active.firstOrNull()?.currentFile ?: "")
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException when started from the background on Android 12+.
                // The transfer still runs; it is just not protected from the process being killed.
                Log.w(VoidArrayApplication.TAG, "Could not start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
