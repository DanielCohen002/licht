package com.djcohen.candletile

import android.content.Context
import android.location.Geocoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/** City search, ZIP lookup and the user's saved places. All calls block: run them off the main thread. */
object Places {
    private val zipRe = Regex("\\d{5}")

    /** City name -> candidate US places (name, state, coordinates, known ZIPs), via Open-Meteo's free geocoder. */
    fun search(query: String): JSONArray {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json&countryCode=US")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val out = JSONArray()
            val res = body.optJSONArray("results") ?: return out
            for (i in 0 until res.length()) {
                val r = res.getJSONObject(i)
                val zips = JSONArray()
                r.optJSONArray("postcodes")?.let { pc ->
                    for (j in 0 until pc.length()) if (pc.getString(j).matches(zipRe)) zips.put(pc.getString(j))
                }
                out.put(
                    JSONObject()
                        .put("name", r.optString("name"))
                        .put("state", r.optString("admin1"))
                        .put("lat", r.getDouble("latitude"))
                        .put("lon", r.getDouble("longitude"))
                        .put("zips", zips)
                )
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** A ZIP for a picked place: the geocoder's own list first, otherwise the phone's reverse geocoder. */
    @Suppress("DEPRECATION")
    fun zipFor(ctx: Context, lat: Double, lon: Double, known: List<String>): String? {
        known.firstOrNull { it.matches(zipRe) }?.let { return it }
        return try {
            if (Geocoder.isPresent()) {
                Geocoder(ctx, Locale.US).getFromLocation(lat, lon, 1)?.firstOrNull()?.postalCode?.take(5)
                    ?.takeIf { it.matches(zipRe) }
            } else null
        } catch (e: Exception) { null }
    }

    /**
     * Checks the ZIP with Chabad.org, switches to it (manual mode) and remembers it in the saved list.
     * Returns Chabad's name for the place. Throws if Chabad does not know the ZIP.
     */
    @Suppress("DEPRECATION")
    fun select(ctx: Context, zip: String, lat: Double?, lon: Double?): String {
        if (!zip.matches(zipRe)) throw IllegalArgumentException("zip")
        val place = Chabad.fetch(zip, LocalDate.now()).second ?: throw IllegalArgumentException("unknown ZIP")
        val s = Store(ctx)
        var la = lat ?: s.lat
        var lo = lon ?: s.lon
        if (lat == null || lon == null) {
            try {
                val a = Geocoder(ctx, Locale.US).getFromLocationName("$zip, USA", 1)?.firstOrNull()
                if (a != null) { la = a.latitude; lo = a.longitude }
            } catch (e: Exception) { /* coordinates only feed the offline fallback */ }
        }
        s.choose(zip, place, la, lo)
        val arr = JSONArray(s.saved)
        var have = false
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("zip") == zip) have = true
        if (!have) {
            arr.put(JSONObject().put("zip", zip).put("label", place).put("lat", la).put("lon", lo))
            s.saved = arr.toString()
        }
        return place
    }

    /** Removes a saved place. Returns true if it was the one currently in use. */
    fun remove(ctx: Context, zip: String): Boolean {
        val s = Store(ctx)
        val arr = JSONArray(s.saved)
        val keep = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("zip") != zip) keep.put(arr.getJSONObject(i))
        s.saved = keep.toString()
        return s.manual && s.zip == zip
    }
}
