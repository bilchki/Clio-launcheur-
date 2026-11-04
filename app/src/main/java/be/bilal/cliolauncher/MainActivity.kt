package be.bilal.cliolauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var appsCache: String? = null
    private var artKey: String = ""
    private var artB64: String = ""
    private lateinit var obd: Obd
    private var lastAuto = 0L

    // Démarrage automatique : au démarrage de l'autoradio et au réveil de l'écran
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { autoStart("wake") }
    }

    private fun autoStart(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastAuto != 0L && now - lastAuto < 120000) return
        lastAuto = now
        handler.postDelayed({ js("window.onAutoStart && window.onAutoStart('$reason')") }, 4000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#05070A"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        // La page est dessinée sur 1280 px de large et le WebView la met à l'échelle de l'écran
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.setSupportZoom(false)
        web.settings.builtInZoomControls = false
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        obd = Obd(
            onData = { json -> js("window.onObd && window.onObd($json)") },
            onStatus = { st -> js("window.onObdStatus && window.onObdStatus('$st')") }
        )

        try { registerReceiver(wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON)) } catch (e: Exception) { }
        if (SystemClock.elapsedRealtime() < 180000) autoStart("boot")

        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED
        ) need.add("android.permission.BLUETOOTH_CONNECT")
        if (need.isNotEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(need.toTypedArray(), 1) else startGps()
    }

    override fun onDestroy() {
        try { obd.stop() } catch (e: Exception) { }
        try { unregisterReceiver(wakeReceiver) } catch (e: Exception) { }
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gpsListener) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGps()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Launcher : le bouton retour ramène simplement à l'écran d'accueil.
        js("window.onBack && window.onBack()")
    }

    override fun onResume() {
        super.onResume()
        appsCache = null
        js("window.onResumeApp && window.onResumeApp()")
    }

    private fun js(code: String) {
        handler.post { web.evaluateJavascript(code, null) }
    }

    // ---------- GPS ----------
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else -1
            js("window.onGps && window.onGps($kmh, ${location.latitude}, ${location.longitude})")
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener)
        } catch (e: Exception) { }
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) js("window.onGps && window.onGps(-1, ${last.latitude}, ${last.longitude})")
        } catch (e: Exception) { }
    }

    // ---------- Outils ----------
    private fun drawableToB64(d: Drawable, size: Int): String {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun bitmapToB64(b: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(b, 200, 200, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun controller(): MediaController? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
        if (list.isEmpty()) return null
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list[0]
    }

    private fun isInstalled(pkg: String): Boolean =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    // ---------- Pont JavaScript <-> Android ----------
    inner class Bridge {

        @JavascriptInterface
        fun apps(): String {
            appsCache?.let { return it }
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            val list = pm.queryIntentActivities(intent, 0)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
            for (r in list) {
                val pkg = r.activityInfo.packageName
                if (pkg == packageName) continue
                val o = JSONObject()
                o.put("label", r.loadLabel(pm).toString())
                o.put("pkg", pkg)
                try { o.put("icon", drawableToB64(r.loadIcon(pm), 96)) } catch (e: Exception) { o.put("icon", "") }
                arr.put(o)
            }
            val s = arr.toString()
            appsCache = s
            return s
        }

        @JavascriptInterface
        fun hasApp(pkg: String): Boolean = isInstalled(pkg)

        @JavascriptInterface
        fun launch(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { startActivity(i); true } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun navigate(query: String, lat: Double, lng: Double): Boolean {
            val hasLL = !(lat == 0.0 && lng == 0.0)
            val q = Uri.encode(query)
            val tries = mutableListOf<Intent>()
            if (isInstalled("com.waze")) {
                val u = if (hasLL) "https://waze.com/ul?ll=$lat,$lng&navigate=yes" else "https://waze.com/ul?q=$q&navigate=yes"
                tries.add(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage("com.waze"))
            }
            val g = if (hasLL) "google.navigation:q=$lat,$lng" else "google.navigation:q=$q"
            tries.add(Intent(Intent.ACTION_VIEW, Uri.parse(g)))
            val geo = if (hasLL) "geo:$lat,$lng?q=$lat,$lng" else "geo:0,0?q=$q"
            tries.add(Intent(Intent.ACTION_VIEW, Uri.parse(geo)))
            for (i in tries) {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return true
                } catch (e: Exception) { }
            }
            return false
        }

        @JavascriptInterface
        fun media(): String {
            val o = JSONObject()
            try {
                val c = controller()
                if (c == null) {
                    o.put("ok", true); o.put("none", true)
                    return o.toString()
                }
                val md = c.metadata
                val st = c.playbackState
                val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                o.put("ok", true)
                o.put("title", title)
                o.put("artist", artist)
                o.put("app", c.packageName)
                o.put("duration", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                o.put("position", st?.position ?: 0L)
                o.put("playing", st?.state == PlaybackState.STATE_PLAYING)
                val key = title + "|" + artist
                if (key != artKey) {
                    artKey = key
                    val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    artB64 = if (bmp != null) bitmapToB64(bmp) else ""
                }
                o.put("art", artB64)
            } catch (e: SecurityException) {
                o.put("ok", false); o.put("perm", true)
            } catch (e: Exception) {
                o.put("ok", false); o.put("error", e.toString())
            }
            return o.toString()
        }

        @JavascriptInterface
        fun mediaCmd(cmd: String) {
            try {
                val t = controller()?.transportControls ?: return
                when (cmd) {
                    "play" -> t.play()
                    "pause" -> t.pause()
                    "next" -> t.skipToNext()
                    "prev" -> t.skipToPrevious()
                }
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun openNotifAccess() {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun openHomeSettings() {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
            }
        }

        @JavascriptInterface
        fun openSettings() {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun mediaPlay() {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun obdStart(mode: String, target: String) { obd.start(mode, target) }

        @JavascriptInterface
        fun obdStop() { obd.stop() }

        @JavascriptInterface
        fun obdCmd(c: String) { obd.pendingCmd = c }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun btInfo(): String {
            val o = JSONObject()
            try {
                val ad = BluetoothAdapter.getDefaultAdapter()
                o.put("present", ad != null)
                o.put("active", ad?.isEnabled ?: false)
                val arr = JSONArray()
                ad?.bondedDevices?.forEach { arr.put((it.name ?: "?") + " (" + it.address + ")") }
                o.put("appairés", arr)
            } catch (e: Exception) {
                o.put("erreur", e.toString())
            }
            return o.toString()
        }

        @JavascriptInterface
        fun diag(): String {
            val o = JSONObject()
            val dm = resources.displayMetrics
            o.put("android_affiche", Build.VERSION.RELEASE)
            o.put("api_reelle", Build.VERSION.SDK_INT)
            o.put("noyau", System.getProperty("os.version") ?: "")
            o.put("ecran", "${dm.widthPixels} x ${dm.heightPixels} px · ${dm.densityDpi} dpi")
            o.put("fabricant", Build.MANUFACTURER)
            o.put("modele", Build.MODEL)
            o.put("marque", Build.BRAND)
            o.put("materiel", Build.HARDWARE)
            o.put("carte", Build.BOARD)
            o.put("produit", Build.PRODUCT)
            o.put("version_app", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" })
            val rx = Regex("(can|bus|mcu|car|auto|link|play|syu|fyt|microntek|hct|ts10|launcher|radio|tpms|obd)", RegexOption.IGNORE_CASE)
            val pk = JSONArray()
            for (p in packageManager.getInstalledPackages(0)) {
                if (rx.containsMatchIn(p.packageName)) pk.put(p.packageName)
            }
            o.put("applis_voiture", pk)
            return o.toString()
        }
    }
}
