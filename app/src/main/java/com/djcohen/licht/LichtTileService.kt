package com.djcohen.licht

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: next candle lighting and when Shabbos ends. Tap opens the weeks page. */
open class LichtTileService : TileService() {
    /** Mini tile: only the two times, drawn big in the icon (a half-size tile shows nothing but the icon). */
    protected open val mini = false

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

    /** Icon with just the two times stacked, as big as the icon box allows. The system tints it, so only alpha matters. */
    private fun timesIcon(top: String, bottom: String): Bitmap {
        val size = 144
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 84f
        }
        val room = size - 2f
        val widest = maxOf(paint.measureText(top), paint.measureText(bottom))
        if (widest > room) paint.textSize = paint.textSize * room / widest
        c.drawText(top, size / 2f, size * 0.48f, paint)
        c.drawText(bottom, size / 2f, size * 0.98f, paint)
        return bmp
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
            if (mini) tile.icon = Icon.createWithBitmap(timesIcon(short(w.fri.candle), short(w.sat.ends)))
        }
        tile.updateTile()
    }
}

/** "Licht mini": a second Quick Settings tile for the half-size slot, showing just the two times. */
class LichtMiniTileService : LichtTileService() {
    override val mini = true
}
