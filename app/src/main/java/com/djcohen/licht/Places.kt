package com.djcohen.licht

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
    fun search(ctx: Context, query: String): JSONArray {
        val out = JSONArray()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=en&format=json")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val res = body.optJSONArray("results") ?: return out
            for (i in 0 until res.length()) {
                val r = res.getJSONObject(i)
                val zips = JSONArray()
                r.optJSONArray("postcodes")?.let { pc ->
                    for (j in 0 until pc.length()) if (pc.getString(j).matches(zipRe)) zips.put(pc.getString(j))
                }
                val cc = r.optString("country_code").uppercase(Locale.US)
                if (cc != "US" && !r.has("id")) continue
                out.put(
                    JSONObject()
                        .put("name", r.optString("name"))
                        .put("state", if (cc == "US") r.optString("admin1") else listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "))
                        .put("cc", cc)
                        .put("cid", if (cc == "US") 0 else ChabadCities.find(ctx, r.optString("name"), r.optString("admin1"), r.optString("country")))
                        .put("geo", r.optLong("id"))
                        .put("tz", r.optString("timezone"))
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

    /** Time zone id for a spot (Open-Meteo), or "" if it can't be found; the phone's zone is used then. Blocks. */
    fun tzFor(lat: Double, lon: Double): String {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m&timezone=auto")
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            if (c.responseCode != 200) "" else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("timezone")
        } catch (e: Exception) { "" } finally { c.disconnect() }
    }

    /** Switches to a non-US city from the search (manual mode) and remembers it. Jerusalem (GeoNames 281184) gets its special key. */
    fun selectWorld(ctx: Context, geo: Long, label: String, lat: Double, lon: Double, cc: String, tz: String, cid: Int): String {
        val key = if (geo == 281184L) Chabad.JLM else "g$geo"
        val s = Store(ctx)
        s.choose(key, label, lat, lon, cc, tz, cid)
        val arr = JSONArray(s.saved)
        var have = false
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("zip") == key) have = true
        if (!have) {
            arr.put(JSONObject().put("zip", key).put("label", label).put("lat", lat).put("lon", lon).put("cc", cc).put("tz", tz).put("cid", cid))
            s.saved = arr.toString()
        }
        return label
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
        s.choose(zip, place, la, lo, "US", tzFor(la, lo))
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
