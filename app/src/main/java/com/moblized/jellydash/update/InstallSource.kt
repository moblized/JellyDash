package com.moblized.jellydash.update

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Android Auto only runs apps built with the Car App Library when Android records them as installed
 * by Google Play. The "Unknown sources" switch doesn't change that. UnknownAuto installs an APK with
 * that record; an ordinary install (browser, file manager, or this app's own updater) wipes it, and
 * JellyDash disappears from the car again.
 */
object InstallSource {
    const val PLAY_STORE = "com.android.vending"
    const val UNKNOWN_AUTO_URL = "https://github.com/GitMag/UnknownAuto/releases/latest"

    fun installer(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /** True when Android Auto will accept JellyDash. */
    fun readyForAndroidAuto(context: Context): Boolean = installer(context) == PLAY_STORE

    /**
     * Copies a downloaded update into the phone's Downloads folder, so it can be installed with
     * UnknownAuto instead of the normal installer. Returns the file name.
     */
    fun saveToDownloads(context: Context, file: File): String {
        if (Build.VERSION.SDK_INT < 29) throw IOException("Saving to Downloads needs Android 10 or newer")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't save to Downloads")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("Couldn't save to Downloads")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return file.name
    }
}
