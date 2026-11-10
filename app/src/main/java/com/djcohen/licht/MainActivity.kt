package com.djcohen.licht

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors

/** Hosts the weeks page (assets/index.html) and feeds it data. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newCachedThreadPool()
    private var insetTop = 0
    private var insetBottom = 0
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF120D18.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            // The page is fully local; never navigate anywhere else.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                pushInsets()
            }
        }
        web.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            // When the keyboard is up, really resize the page (not just pad it) so the sheet sits above it.
            val lp = v.layoutParams as android.widget.FrameLayout.LayoutParams
            if (lp.bottomMargin != ime) { lp.bottomMargin = ime; v.layoutParams = lp }
            insetTop = b.top
            insetBottom = if (ime > 0) 0 else b.bottom
            if (loaded) pushInsets()
            ins
        }
        web.loadUrl("file:///android_asset/index.html")

        if (!Loc.hasPermission(this)) requestPermissions(arrayOf(Loc.PERM), 1)

        // Back closes the location sheet first, then leaves the app.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (sheetOpen) push("window.__closeSheet()") else finish()
        }
    }

    @Volatile private var sheetOpen = false

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) refreshLocationAsync()
    }

    private fun pushInsets() {
        val d = resources.displayMetrics.density
        push("window.setInsets(${insetTop / d}, ${insetBottom / d})")
    }

    private fun push(js: String) = runOnUiThread { web.evaluateJavascript(js, null) }

    private fun refreshLocationAsync() = io.execute {
        Loc.refresh(applicationContext)
        push("window.__place(${JSONObject.quote(Store(applicationContext).label)})")
        push("window.__reload()")
    }

    inner class Bridge {
        @JavascriptInterface
        fun sheet(open: Boolean) { sheetOpen = open }

        /** Switch where the times come from ("chabad" or "hebcal"). */
        @JavascriptInterface
        fun setSource(src: String) {
            if (src != "chabad" && src != "hebcal") return
            Store(applicationContext).src = src
            push("window.__reload()")
        }

        @JavascriptInterface
        fun choose(zip: String, label: String, lat: Double, lon: Double) {
            Store(applicationContext).choose(zip, label, lat, lon)
            push("window.__place(${JSONObject.quote(label)})")
            push("window.__reload()")
        }

        /** Go back to following the phone's location. */
        @JavascriptInterface
        fun useLocal() {
            val s = Store(applicationContext)
            s.manual = false
            s.restoreAuto()
            if (!Loc.hasPermission(this@MainActivity)) {
                runOnUiThread { requestPermissions(arrayOf(Loc.PERM), 1) }
            }
            refreshLocationAsync()
        }

        /** Use a typed ZIP (validated with Chabad.org) and remember it. */
        @JavascriptInterface
        fun addZip(zip: String) {
            net.execute {
                try {
                    val place = Places.select(applicationContext, zip, null, null)
                    push("window.__place(${JSONObject.quote(place)})")
                    push("window.__reload()")
                } catch (e: Exception) {
                    push("window.__zipError()")
                }
            }
        }

        /** City search; the page gets a list of candidate places. */
        @JavascriptInterface
        fun search(query: String, id: Int) {
            net.execute {
                val out = try { Places.search(query).toString() } catch (e: Exception) { "null" }
                push("window.__results($id, $out)")
            }
        }

        /** A place picked from the search results. */
        @JavascriptInterface
        fun pick(lat: Double, lon: Double, zips: String) {
            net.execute {
                try {
                    val zip = Places.zipFor(applicationContext, lat, lon, zips.split(",")) ?: throw IllegalStateException("no ZIP")
                    val place = Places.select(applicationContext, zip, lat, lon)
                    push("window.__place(${JSONObject.quote(place)})")
                    push("window.__reload()")
                } catch (e: Exception) {
                    push("window.__zipError()")
                }
            }
        }

        /** Forget a saved place; if it was in use, go back to the phone's location. */
        @JavascriptInterface
        fun remove(zip: String) {
            net.execute {
                if (Places.remove(applicationContext, zip)) useLocal() else push("window.__refreshSheet()")
            }
        }

        @JavascriptInterface
        fun info(): String {
            val s = Store(applicationContext)
            return JSONObject()
                .put("manual", s.manual)
                .put("src", s.src)
                .put("zip", s.zip)
                .put("saved", org.json.JSONArray(s.saved))
                .put("place", s.label)
                .put("today", LocalDate.now().toString())
                .put("target", Weeks.targetFriday(applicationContext).toString())
                .toString()
        }

        @JavascriptInterface
        fun week(iso: String, id: Int) {
            io.execute {
                val json = try {
                    Weeks.json(Weeks.load(applicationContext, LocalDate.parse(iso), true))
                } catch (e: Exception) { "null" }
                push("window.__week($id, $json)")
            }
        }

        @JavascriptInterface
        fun refreshLocation() = refreshLocationAsync()
    }
}
