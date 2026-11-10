package com.djcohen.licht

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: next candle lighting and when Shabbos ends. Tap opens the weeks page. */
class LichtTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onTileAdded() = render(null)

    override fun onStartListening() {
        // Show what is cached right away, then refresh from the network.
        val app = applicationContext
        render(Weeks.load(app, Weeks.targetFriday(app), false))
        Thread {
            try {
                Loc.refresh(app)
                val w = Weeks.load(app, Weeks.targetFriday(app), true)
                main.post { render(w) }
            } catch (e: Exception) { /* keep what is shown */ }
        }.start()
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun short(t: String?): String = when {
        t == null -> "--"
        t.endsWith(" PM") -> t.removeSuffix(" PM")
        else -> t.replace(" AM", "am")
    }

    private fun render(w: Week?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = Tile.STATE_ACTIVE
        if (w == null) {
            tile.label = "Licht"
            tile.subtitle = "Tap to open"
        } else {
            tile.label = "Candles ${short(w.fri.candle)}"
            tile.subtitle = "Ends Sat ${short(w.sat.ends)}"
        }
        tile.updateTile()
    }
}
