package com.moblized.jellydash.downloads

import android.app.Notification
import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R

/** Runs downloads in the background with a progress notification, and picks them back up on Wi-Fi. */
@OptIn(UnstableApi::class)
class JellyDashDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL,
    R.string.downloads_channel,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL) }

    override fun getDownloadManager(): DownloadManager = Graph.downloads(this).start()

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val count = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val message = if (count == 1) "1 download" else "$count downloads"
        return helper.buildProgressNotification(this, R.drawable.ic_download, open, message, downloads, notMetRequirements)
    }

    companion object {
        private const val NOTIFICATION_ID = 8
        private const val JOB_ID = 81
        private const val CHANNEL = "downloads"
    }
}
