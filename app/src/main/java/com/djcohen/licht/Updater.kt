package com.djcohen.licht

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the latest Licht release, only when the user taps "Check for updates".
 * It never downloads or installs anything; the page just offers a link to the release.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/DanielCohen002/licht/releases/latest"
    const val LINK_PREFIX = "https://github.com/DanielCohen002/licht/"

    /** Result for the page: {state: "uptodate" | "available" | "error", current, latest, url}. */
    fun check(current: String): JSONObject = try {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Licht")
            check(c.responseCode == 200) { "GitHub answered ${c.responseCode}" }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name").removePrefix("v")
            JSONObject()
                .put("state", if (isNewer(tag, current)) "available" else "uptodate")
                .put("current", current)
                .put("latest", tag)
                .put("url", j.optString("html_url", LINK_PREFIX + "releases/latest"))
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        JSONObject().put("state", "error").put("current", current)
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val d = l.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
