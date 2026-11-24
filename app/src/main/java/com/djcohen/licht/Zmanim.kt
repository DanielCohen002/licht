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

    /** Jerusalem lights candles 40 minutes before sunset (custom of the city); everywhere else it is 18. On by default. */
    var jlm40: Boolean
        get() = sp.getBoolean("jlm40", true)
        set(v) = sp.edit().putBoolean("jlm40", v).apply()

    /** True when the current place is in Jerusalem (Israel, within about 14 km of the city centre). */
    fun inJerusalem(): Boolean {
        if (country != "IL") return false
        val d = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, 31.7767, 35.2345, d)
        return d[0] < 14000f
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
            .putString("lon", sp.getString("a_lon", lon.toString())).putString("tz", "").putInt("cid", 0).apply()
    }

    /**
     * [zip] is a 5-digit US ZIP, "JLM" (Jerusalem) or "g<GeoNames id>" for any other city in the world.
     * [tz] is the place's time zone id (blank = the phone's own).
     */
    fun choose(zip: String, label: String, lat: Double, lon: Double, country: String = "US", tz: String = "", cid: Int = 0) {
        if (!manual) saveAuto()
        sp.edit().putString("zip", zip).putString("country", if (zip == Chabad.JLM) "IL" else country).putString("label", label)
            .putString("lat", lat.toString()).putString("lon", lon.toString())
            .putString("tz", if (zip == Chabad.JLM) "Asia/Jerusalem" else tz)
            .putInt("cid", cid)
            .putBoolean("manual", true).apply()
    }

    /** Chabad.org's own city number for the chosen non-US place, or 0 when Chabad has no listing for it. */
    val cid: Int get() = sp.getInt("cid", 0)

    /** Time zone of the chosen place; the phone's own when blank or unknown. */
    val zone: ZoneId
        get() = try { sp.getString("tz", "")!!.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) } } catch (e: Exception) { null }
            ?: ZoneId.systemDefault()

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
    /** Pseudo-ZIP meaning "Jerusalem" (Chabad location 247, Hebcal geonameid 281184). */
    const val JLM = "JLM"

    private const val T = "(\\d{1,2}:\\d{2}\\s*[AP]M)"
    private val candleRe = Regex("<title>\\s*Candle Lighting[^<]*?-\\s*$T", RegexOption.IGNORE_CASE)
    private val sunsetRe = Regex("<title>\\s*Sunset[^<]*?-\\s*$T", RegexOption.IGNORE_CASE)
    private val endsRe = Regex("<title>[^<]*?Ends[^<]*?-\\s*$T", RegexOption.IGNORE_CASE)
    private val placeRe = Regex("Halachic Times for (.+?) on ")

    private fun norm(s: String?) = s?.replace(Regex("\\s+"), " ")?.trim()?.uppercase(Locale.US)

    /** Returns the day's times and Chabad's name for the place. Throws on any network/parse problem. */
    fun fetch(zip: String, d: LocalDate): Pair<Times, String?> {
        val url = URL(
            "https://www.chabad.org/tools/rss/zmanim.xml?" +
                (if (zip == JLM) "locationid=247&locationtype=1" else if (zip.startsWith("c")) "locationid=${zip.drop(1)}&locationtype=1" else "locationid=$zip&locationtype=2") +
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

/** Chabad.org's list of non-US cities (assets/chabad_cities.tsv: id, city, region), matched to search results by name. */
object ChabadCities {
    private class Row(val id: Int, val city: String, val region: String)
    @Volatile private var rows: List<Row>? = null

    private fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.US).replace("&", "and"), java.text.Normalizer.Form.NFD)
            .replace(Regex("[^a-z0-9]"), "")

    private fun table(ctx: Context): List<Row> {
        rows?.let { return it }
        val t = try {
            ctx.assets.open("chabad_cities.tsv").bufferedReader().useLines { ls ->
                ls.mapNotNull { l ->
                    val p = l.split("	")
                    val id = p.getOrNull(0)?.toIntOrNull()
                    if (p.size == 3 && id != null) Row(id, norm(p[1]), norm(p[2])) else null
                }.toList()
            }
        } catch (e: Exception) { emptyList() }
        rows = t
        return t
    }

    /** Chabad's id for a place like "Paris" / "Île-de-France" / "France", or 0 if Chabad does not list it. */
    fun find(ctx: Context, name: String, admin1: String, country: String): Int {
        val n = norm(name)
        if (n.length < 3) return 0
        val a = norm(admin1)
        val c = norm(country)
        val hits = table(ctx).filter { r ->
            (r.city == n || (n.length >= 4 && r.city.startsWith(n))) &&
                ((c.isNotEmpty() && r.region.contains(c)) || (a.length >= 3 && r.region.contains(a)))
        }
        return (hits.firstOrNull { it.city == n } ?: hits.firstOrNull())?.id ?: 0
    }
}

/** Hebcal's free Shabbat API (candle lighting, Havdalah at 8.5 degrees, and the Torah portion). */
object Hebcal {
    /** Local time from the item's ISO date ("2026-11-27T15:55:00+02:00"), shown as "3:55 PM". */
    private fun t12(date: String): String? =
        try { LocalTime.parse(date.substring(11, 16)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) } catch (e: Exception) { null }

    /** One Shabbos for the Friday [friday]. Throws on any network or parse problem. */
    fun fetch(zip: String, friday: LocalDate): JSONObject {
        val url = URL(
            "https://www.hebcal.com/shabbat?cfg=json&" +
                (if (zip == Chabad.JLM) "geonameid=281184" else if (zip.startsWith("g")) "geonameid=${zip.drop(1)}" else "zip=$zip") + "&M=on" +
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
                    "candles" -> if (date.startsWith(friday.toString())) t12(date)?.let { t -> out.put("c", t) }
                    "havdalah" -> if (date.startsWith(sat)) t12(date)?.let { t -> out.put("e", t) }
                    "parashat" -> if (date.startsWith(sat)) out.put("p", it.optString("title").removePrefix("Parashat ").removePrefix("Parshas "))
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }
}

/** Offline fallback: NOAA sun position with Chabad's rules (candles 18 min before sunset, 40 in Jerusalem, Shabbos ends at 8.5 degrees). */
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

    fun day(date: LocalDate, lat: Double, lon: Double, candleMin: Int = 18, zone: ZoneId = ZoneId.systemDefault()): Times {
        val offMin = zone.rules.getOffset(date.atTime(12, 0).atZone(zone).toInstant())
            .totalSeconds / 60.0
        val sunset = event(date, lat, lon, -0.833, true)
        val ends = event(date, lat, lon, -8.5, true)
        return Times(
            sunset?.let { fmt(it - candleMin + offMin) },
            sunset?.let { fmt(it + offMin) },
            ends?.let { fmt(it + offMin) },
        )
    }
}

object Weeks {
    private val parse12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun parseTime(s: String?): LocalTime? =
        try { if (s == null) null else LocalTime.parse(s, parse12) } catch (e: Exception) { null }

    /** [t] ("4:36 PM") minus [m] minutes, in the same format; null if [t] is missing. */
    private fun minus(t: String?, m: Int): String? =
        parseTime(t)?.minusMinutes(m.toLong())?.format(parse12)?.uppercase(Locale.US)

    /** Loads one Shabbos. [net] lets it call Chabad.org for days not cached yet; otherwise it only reads the cache. */
    fun load(ctx: Context, friday: LocalDate, net: Boolean): Week {
        val s = Store(ctx)
        val sat = friday.plusDays(1)
        val jlm = s.inJerusalem()
        val usZip = s.country == "US" && s.zip.matches(Regex("\\d{5}"))
        // A city picked from the worldwide search: only Hebcal covers these (Chabad.org needs a ZIP or its own city list).
        val world = !jlm && !usZip && s.manual && s.zip.matches(Regex("g\\d+"))
        val lead = if (jlm && s.jlm40) 40 else 18
        val calcFri = Solar.day(friday, s.lat, s.lon, lead, s.zone)
        val calcSat = Solar.day(sat, s.lat, s.lon, 18, s.zone)
        val cid = if (world) s.cid else 0
        val hebKey = if (jlm) Chabad.JLM else s.zip
        if ((s.src == "hebcal" && (jlm || usZip || world)) || (world && cid == 0)) {
            val zip = hebKey
            var h = s.getHebcal(zip, friday)
            if (h == null && net) {
                try { h = Hebcal.fetch(zip, friday); s.putHebcal(zip, friday, h) } catch (e: Exception) { /* fall back below */ }
            }
            if (h != null && h.has("c")) {
                // Hebcal does not list sunset, so show the calculated one (within a minute).
                return Week(
                    friday,
                    // Hebcal's own candle time is 40 min in Jerusalem; with the option off use 18 before sunset.
                    Times(if (jlm && !s.jlm40) minus(calcFri.sunset, 18) else h.optString("c"), calcFri.sunset, null),
                    Times(null, calcSat.sunset, h.optString("e").ifEmpty { calcSat.ends }),
                    "hebcal",
                    h.optString("p").ifEmpty { null },
                )
            }
        } else if (jlm || usZip || world) {
            val zip = if (jlm) Chabad.JLM else if (world) "c$cid" else s.zip
            var f = s.getDay(zip, friday)
            var a = s.getDay(zip, sat)
            if (net) {
                try {
                    if (f == null) {
                        val (t, place) = Chabad.fetch(zip, friday)
                        if (t.candle != null) { s.putDay(zip, friday, t); f = t }
                        if (place != null && !jlm && zip == s.zip) s.label = place
                    }
                    if (a == null) {
                        val (t, _) = Chabad.fetch(zip, sat)
                        if (t.ends != null) { s.putDay(zip, sat, t); a = t }
                    }
                } catch (e: Exception) { /* fall through to the calculation */ }
            }
            // Chabad's own candle time is 40 min in Jerusalem; with the option off use 18 before sunset.
            if (jlm && !s.jlm40 && f != null) f = Times(minus(f.sunset, 18) ?: f.candle, f.sunset, f.ends)
            if (f != null && a != null) return Week(friday, f, a, "chabad", parshaFor(s, hebKey, friday, net))
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
    /** [now] is the clock time in the chosen place's own time zone, since Shabbos-end is a time there. */
    fun targetFriday(ctx: Context, now: LocalDateTime = LocalDateTime.now(Store(ctx).zone)): LocalDate {
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
