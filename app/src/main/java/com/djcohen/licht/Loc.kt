package com.djcohen.licht

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale

/** Reads the phone's last known location and turns it into a ZIP for Chabad.org. Call off the main thread. */
object Loc {
    const val PERM = android.Manifest.permission.ACCESS_COARSE_LOCATION

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns true if a fresh location was read and stored. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun refresh(ctx: Context): Boolean {
        if (!hasPermission(ctx)) return false
        if (Store(ctx).manual) return false   // the user picked a place by hand
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        val best = try {
            lm.getProviders(true).mapNotNull {
                try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
            }.maxByOrNull { it.time }
        } catch (e: Exception) { null } ?: return false

        val s = Store(ctx)
        val d = FloatArray(1)
        Location.distanceBetween(s.lat, s.lon, best.latitude, best.longitude, d)
        val moved = d[0] > 2000f
        s.lat = best.latitude
        s.lon = best.longitude

        if (moved || !s.geocoded) {
            try {
                if (Geocoder.isPresent()) {
                    val a = Geocoder(ctx, Locale.US).getFromLocation(best.latitude, best.longitude, 1)?.firstOrNull()
                    if (a != null) {
                        val zip = a.postalCode?.take(5)
                        s.country = a.countryCode ?: s.country
                        if (zip != null && zip.matches(Regex("\\d{5}"))) s.zip = zip
                        val city = a.locality ?: a.subAdminArea ?: ""
                        s.label = listOf(city, a.adminArea ?: "", if (s.country == "US") zip ?: "" else "")
                            .filter { it.isNotBlank() }.joinToString(", ").ifBlank { s.label }
                        s.geocoded = true
                    }
                }
            } catch (e: Exception) { /* keep the stored place */ }
        }
        s.saveAuto()
        return true
    }
}
