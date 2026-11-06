package com.moblized.jellydash.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a release APK and hands it to Android's installer. Every build shares one signing key, so your sign-in carries over. */
class Updater(private val context: Context) {
    val installedVersionCode: Int
        get() = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
        }.getOrDefault(0)

    val installedVersionName: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "JellyDash")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
        val c = open(Updates.LATEST_URL)
        try {
            if (c.responseCode !in 200..299) throw IOException("GitHub answered ${c.responseCode}")
            Updates.parseRelease(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: throw IOException("This release has no APK attached")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the one being installed
        val out = File(dir, "JellyDash-${release.versionName}.apk")
        val c = open(url)
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw IOException("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkBytes
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != reported) {
                                reported = pct
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        out
    }

    /** Android asks once whether the app may install updates; true when it already may. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
