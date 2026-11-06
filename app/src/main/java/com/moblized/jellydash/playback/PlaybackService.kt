package com.moblized.jellydash.playback

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R

/**
 * Keeps the video playing, and the phone's GPS reading for the parked check, while the phone's own
 * screen is off. Android shows a "Playing on Android Auto" notification while it runs.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = Graph.playback(this).state.value?.title ?: getString(R.string.app_name)
        val notification = notification(this, title)
        val canLocate = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val started = runCatching {
            val type = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or (if (canLocate) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess || runCatching {
            // Location can be refused here; playback alone still keeps things running.
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 7

        /**
         * A plain start, not a "foreground start": Android Auto being connected counts as the app
         * being in use, and if Android refuses the foreground part there's no crash, playback just
         * relies on Android Auto keeping the app awake.
         */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }

        private fun notification(context: Context, title: String): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pending = open?.let {
                android.app.PendingIntent.getActivity(context, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText("Playing on Android Auto")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pending)
                .build()
        }
    }
}
