package com.moblized.jellydash.update

import org.json.JSONObject

/** A published build on GitHub Releases. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    /** What changed, from the release notes. */
    val notes: String,
    val apkUrl: String?,
    val apkBytes: Long,
)

object Updates {
    const val REPO = "moblized/jellydash"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    const val SNOOZE_MS = 24 * 60 * 60 * 1000L

    /** "v1.0.21" is build 21: builds are numbered by their CI run, which is also the version code. */
    fun versionCodeOf(tag: String): Int? = Regex("""(\d+)\s*$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Reads GitHub's release JSON; null for anything that isn't a usable release. */
    fun parseRelease(json: String): AppRelease? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        val code = versionCodeOf(tag) ?: return null
        val assets = o.optJSONArray("assets")
        var url: String? = null
        var bytes = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    bytes = a.optLong("size")
                    break
                }
            }
        }
        val notes = o.optString("body").lines()
            .filterNot { it.startsWith("Co-Authored-By:") || it.startsWith("Claude-Session:") }
            .joinToString("\n")
            .trim()
        return AppRelease(code, tag.removePrefix("v"), notes, url, bytes)
    }
}
