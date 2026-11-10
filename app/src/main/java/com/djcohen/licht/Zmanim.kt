package com.djcohen.licht

import android.content.Context
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/** Times for one civil day, already formatted like "4:22 PM". */
data class Times(val candle: String?, val sunset: String?, val ends: String?)

/** One Shabbos: the Friday (candle lighting) and the Saturday (Shabbos ends). */
data class Week(val friday: LocalDate, val fri: Times, val sat: Times, val source: String, val parsha: String? = null)

/** Where the user is, plus a per-day cache of Chabad.org results. */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("licht", Context.MODE_PRIVATE)

    // Neutral default (New York) so the tile works before the first location fix.
    var zip: String
        get() = sp.getString("zip", "10001")!!
        set(v) = sp.edit().putString("zip", v).apply()
    var country: String
        get() = sp.getString("country", "US")!!
        set(v) = sp.edit().putString("country", v).apply()
    var label: String
        get() = sp.getString("label", "New York, NY 10001")!!
        set(v) = sp.edit().putString("label", v).apply()
    var lat: Double
        get() = sp.getString("lat", "40.7506")!!.toDouble()
        set(v) = sp.edit().putString("lat", v.toString()).apply()
    var lon: Double
        get() = sp.getString("lon", "-73.9971")!!.toDouble()
        set(v) = sp.edit().putString("lon", v.toString()).apply()
    var geocoded: Boolean
        get() = sp.getBoolean("geocoded", false)
        set(v) = sp.edit().putBoolean("geocoded", v).apply()

    /** Where the times come from: "chabad" (Chabad.org) or "hebcal" (Hebcal's zmanim). */
    var src: String
        get() = sp.getString("src", "chabad")!!
        set(v) = sp.edit().putString("src", v).apply()

    /** Hebcal results are cached per Friday: JSON {c: candles, e: Shabbos ends, p: parsha}. */
    fun getHebcal(zip: String, friday: LocalDate): JSONObject? =
        sp.getString("h:$zip:$friday", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    fun putHebcal(zip: String, friday: LocalDate, o: JSONObject) {
        sp.edit().putString("h:$zip:$friday", o.toString()).apply()
    }

    /** True when the user picked a place by hand; the phone's location is then ignored. */
    var manual: Boolean
        get() = sp.getBoolean("manual", false)
        set(v) = sp.edit().putBoolean("manual", v).apply()

    /** ZIPs the user added themselves: JSON array of {zip,label,lat,lon}. */
    var saved: String
        get() = sp.getString("saved", "[]")!!
        set(v) = sp.edit().putString("saved", v).apply()

    /** Remember the last place found from the phone so "My location" can come back instantly. */
    fun saveAuto() {
        sp.edit().putString("a_zip", zip).putString("a_country", country).putString("a_label", label)
            .putString("a_lat", lat.toString()).putString("a_lon", lon.toString()).apply()
    }

    fun restoreAuto() {
        val z = sp.getString("a_zip", null) ?: return
        sp.edit().putString("zip", z).putString("country", sp.getString("a_country", "US"))
            .putString("label", sp.getString("a_label", label)).putString("lat", sp.getString("a_lat", lat.toString()))
            .putString("lon", sp.getString("a_lon", lon.toString())).apply()
    }

    fun choose(zip: String, label: String, lat: Double, lon: Double) {
        if (!manual) saveAuto()
        sp.edit().putString("zip", zip).putString("country", "US").putString("label", label)
            .putString("lat", lat.toString()).putString("lon", lon.toString())
            .putBoolean("manual", true).apply()
    }

    fun getDay(zip: String, d: LocalDate): Times? {
        val raw = sp.getString("d:$zip:$d", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Times(o.optString("c").ifEmpty { null }, o.optString("s").ifEmpty { null }, o.optString("e").ifEmpty { null })
        } catch (e: Exception) { null }
    }

    fun putDay(zip: String, d: LocalDate, t: Times) {
        val o = JSONObject().put("c", t.candle ?: "").put("s", t.sunset ?: "").put("e", t.ends ?: "")
        sp.edit().putString("d:$zip:$d", o.toString()).apply()
    }
}

/** Chabad.org's public zmanim RSS feed (the same numbers as chabad.org/zmanim). */
object Chabad {
    private const val T = "(\\d{1,2}:\\d{2}\\s*[AP]M)"
    private val candleRe = Regex("<title>\\s*Candle Lighting[^<]*?-\\s*$T", RegexOption.IGNORE_CASE)
    private val sunsetRe = Regex("<title>\\s*Sunset[^<]*?-\\s*$T", RegexOption.IGNORE_CASE)
    private val endsRe = Regex("<title>[^<]*?Ends[^<]*?-\\s*$T", RegexOption.IGNORE_CASE)
    private val placeRe = Regex("Halachic Times for (.+?) on ")

    private fun norm(s: String?) = s?.replace(Regex("\\s+"), " ")?.trim()?.uppercase(Locale.US)

    /** Returns the day's times and Chabad's name for the place. Throws on any network/parse problem. */
    fun fetch(zip: String, d: LocalDate): Pair<Times, String?> {
        val url = URL(
            "https://www.chabad.org/tools/rss/zmanim.xml?locationid=$zip&locationtype=2" +
                "&tdate=${d.monthValue}/${d.dayOfMonth}/${d.year}"
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Licht/1.0")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val xml = c.inputStream.bufferedReader().use { it.readText() }
            if (!xml.contains("<rss")) throw IOException("unexpected document")
            val t = Times(
                norm(candleRe.find(xml)?.groupValues?.get(1)),
                norm(sunsetRe.find(xml)?.groupValues?.get(1)),
                norm(endsRe.find(xml)?.groupValues?.get(1)),
            )
            return t to placeRe.find(xml)?.groupValues?.get(1)?.trim()
        } finally {
            c.disconnect()
        }
    }
}

/** Hebcal's free Shabbat API (candle lighting, Havdalah at 8.5 degrees, and the Torah portion). */
object Hebcal {
    private fun t12(s: String): String? {
        val m = Regex("(\\d{1,2}:\\d{2})\\s*([ap]m)", RegexOption.IGNORE_CASE).find(s) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2].uppercase(Locale.US)}"
    }

    /** One Shabbos for the Friday [friday]. Throws on any network or parse problem. */
    fun fetch(zip: String, friday: LocalDate): JSONObject {
        val url = URL(
            "https://www.hebcal.com/shabbat?cfg=json&zip=$zip&M=on" +
                "&gy=${friday.year}&gm=${friday.monthValue}&gd=${friday.dayOfMonth}"
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Licht/1.0 (Android)")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val items = body.optJSONArray("items") ?: throw IOException("no items")
            val out = JSONObject()
            val sat = friday.plusDays(1).toString()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val date = it.optString("date")
                when (it.optString("category")) {
                    "candles" -> if (date.startsWith(friday.toString())) t12(it.optString("title"))?.let { t -> out.put("c", t) }
                    "havdalah" -> if (date.startsWith(sat)) t12(it.optString("title"))?.let { t -> out.put("e", t) }
                    "parashat" -> if (date.startsWith(sat)) out.put("p", it.optString("title").removePrefix("Parashat ").removePrefix("Parshas "))
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }
}

/** Offline fallback: NOAA sun position with Chabad's rules (candles 18 min before sunset, Shabbos ends at 8.5 degrees). */
object Solar {
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** UTC minutes after 0h UTC of [date] for the sun reaching [alt] degrees, or null (polar). */
    fun event(date: LocalDate, lat: Double, lon: Double, alt: Double, evening: Boolean): Double? {
        val jd0 = date.toEpochDay() + 2440587.5
        var t = if (evening) 1080.0 else 360.0
        repeat(3) {
            val c = (jd0 + t / 1440.0 - 2451545.0) / 36525.0
            val l0 = (280.46646 + c * (36000.76983 + c * 0.0003032)) % 360
            val m = 357.52911 + c * (35999.05029 - 0.0001537 * c)
            val e = 0.016708634 - c * (0.000042037 + 0.0000001267 * c)
            val ctr = sin(rad(m)) * (1.914602 - c * (0.004817 + 0.000014 * c)) +
                sin(rad(2 * m)) * (0.019993 - 0.000101 * c) + sin(rad(3 * m)) * 0.000289
            val om = 125.04 - 1934.136 * c
            val lam = l0 + ctr - 0.00569 - 0.00478 * sin(rad(om))
            val eps0 = 23 + (26 + (21.448 - c * (46.815 + c * (0.00059 - c * 0.001813))) / 60) / 60
            val eps = eps0 + 0.00256 * cos(rad(om))
            val decl = deg(kotlin.math.asin(sin(rad(eps)) * sin(rad(lam))))
            val y = tan(rad(eps / 2)).let { it * it }
            val eqt = 4 * deg(
                y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                    0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m))
            )
            val cosH = (sin(rad(alt)) - sin(rad(lat)) * sin(rad(decl))) / (cos(rad(lat)) * cos(rad(decl)))
            if (cosH < -1.0 || cosH > 1.0) return null
            val ha = deg(acos(cosH))
            t = 720 - 4 * lon - eqt + (if (evening) 4 * ha else -4 * ha)
        }
        return t
    }

    private fun fmt(localMinutes: Double): String {
        val r = round(localMinutes).toInt()
        val h24 = ((r / 60) % 24 + 24) % 24
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return "%d:%02d %s".format(Locale.US, h12, ((r % 60) + 60) % 60, if (h24 >= 12) "PM" else "AM")
    }

    fun day(date: LocalDate, lat: Double, lon: Double): Times {
        val offMin = ZoneId.systemDefault().rules.getOffset(date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant())
            .totalSeconds / 60.0
        val sunset = event(date, lat, lon, -0.833, true)
        val ends = event(date, lat, lon, -8.5, true)
        return Times(
            sunset?.let { fmt(it - 18 + offMin) },
            sunset?.let { fmt(it + offMin) },
            ends?.let { fmt(it + offMin) },
        )
    }
}

object Weeks {
    private val parse12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun parseTime(s: String?): LocalTime? =
        try { if (s == null) null else LocalTime.parse(s, parse12) } catch (e: Exception) { null }

    /** Loads one Shabbos. [net] lets it call Chabad.org for days not cached yet; otherwise it only reads the cache. */
    fun load(ctx: Context, friday: LocalDate, net: Boolean): Week {
        val s = Store(ctx)
        val sat = friday.plusDays(1)
        val calcFri = Solar.day(friday, s.lat, s.lon)
        val calcSat = Solar.day(sat, s.lat, s.lon)
        if (s.src == "hebcal" && s.country == "US" && s.zip.matches(Regex("\\d{5}"))) {
            val zip = s.zip
            var h = s.getHebcal(zip, friday)
            if (h == null && net) {
                try { h = Hebcal.fetch(zip, friday); s.putHebcal(zip, friday, h) } catch (e: Exception) { /* fall back below */ }
            }
            if (h != null && h.has("c")) {
                // Hebcal does not list sunset, so show the calculated one (within a minute).
                return Week(
                    friday,
                    Times(h.optString("c"), calcFri.sunset, null),
                    Times(null, calcSat.sunset, h.optString("e").ifEmpty { calcSat.ends }),
                    "hebcal",
                    h.optString("p").ifEmpty { null },
                )
            }
        } else if (s.country == "US" && s.zip.matches(Regex("\\d{5}"))) {
            val zip = s.zip
            var f = s.getDay(zip, friday)
            var a = s.getDay(zip, sat)
            if (net) {
                try {
                    if (f == null) {
                        val (t, place) = Chabad.fetch(zip, friday)
                        if (t.candle != null) { s.putDay(zip, friday, t); f = t }
                        if (place != null && zip == s.zip) s.label = place
                    }
                    if (a == null) {
                        val (t, _) = Chabad.fetch(zip, sat)
                        if (t.ends != null) { s.putDay(zip, sat, t); a = t }
                    }
                } catch (e: Exception) { /* fall through to the calculation */ }
            }
            if (f != null && a != null) return Week(friday, f, a, "chabad", parshaFor(s, zip, friday, net))
        }
        return Week(friday, calcFri, calcSat, "calc")
    }

    /** Chabad.org's feed has no Torah portion, so take it from Hebcal (cached, shared with the Hebcal source). */
    private fun parshaFor(s: Store, zip: String, friday: LocalDate, net: Boolean): String? {
        var h = s.getHebcal(zip, friday)
        if (h == null && net) {
            try { h = Hebcal.fetch(zip, friday); s.putHebcal(zip, friday, h) } catch (e: Exception) { /* no parsha this time */ }
        }
        return h?.optString("p")?.ifEmpty { null }
    }

    /** The Friday of the nearest upcoming Shabbos (the current one stays until Shabbos ends). */
    fun targetFriday(ctx: Context, now: LocalDateTime = LocalDateTime.now()): LocalDate {
        val today = now.toLocalDate()
        return when (today.dayOfWeek) {
            DayOfWeek.FRIDAY -> today
            DayOfWeek.SATURDAY -> {
                val fri = today.minusDays(1)
                val end = parseTime(load(ctx, fri, false).sat.ends)
                if (end != null && now.toLocalTime().isAfter(end)) today.plusDays(6) else fri
            }
            else -> today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    private fun hebrew(d: LocalDate): String {
        val ms = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SimpleDateFormat("d MMMM y", ULocale("en_US@calendar=hebrew")).format(Date(ms))
    }

    private fun times(t: Times) = JSONObject()
        .put("candle", t.candle ?: JSONObject.NULL)
        .put("sunset", t.sunset ?: JSONObject.NULL)
        .put("ends", t.ends ?: JSONObject.NULL)

    fun json(w: Week): String {
        val sat = w.friday.plusDays(1)
        return JSONObject()
            .put("friday", w.friday.toString())
            .put("source", w.source)
            .put("parsha", w.parsha ?: JSONObject.NULL)
            .put("fri", times(w.fri).put("date", w.friday.format(dayFmt)).put("heb", hebrew(w.friday)))
            .put("sat", times(w.sat).put("date", sat.format(dayFmt)).put("heb", hebrew(sat)))
            .toString()
    }
}
