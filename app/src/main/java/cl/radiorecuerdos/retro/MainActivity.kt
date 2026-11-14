package cl.radiorecuerdos.retro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.analytics.FirebaseAnalytics
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pantalla principal: la interfaz retro está en assets/index.html.
 * El audio lo maneja RadioService, así sigue sonando con la app minimizada o la pantalla apagada.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var audio: AudioManager
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingPlay = false
    private var pageReady = false
    private val io = Executors.newSingleThreadExecutor()
    private var latestBuild = 0L

    companion object {
        const val WEB = "https://recuerdosretro.com"
        const val REPO_API = "https://api.github.com/repos/michaelpalmad-lang/radio-recuerdos-retro./releases/latest"
        const val APK_URL = "https://github.com/michaelpalmad-lang/radio-recuerdos-retro./releases/latest/download/radio-recuerdos-retro.apk"
        const val STATION = "Radio Recuerdos Retro"
    }

    private fun log(event: String, params: Bundle? = null) {
        try { FirebaseAnalytics.getInstance(this).logEvent(event, params) } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#120624"))
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushState()
                pushVolume()
                pushNotif()
                pushUpdate()
            }
        }
        web.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Volver atrás minimiza: la radio sigue sonando.
                moveTaskToBack(true)
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        PushService.setup(this)
        connect()
        checkUpdate()
    }

    // ---------- Aviso de actualización ----------

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private fun installedBuild(): Long = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { 0L }

    /** Revisa en GitHub (al abrir o volver a la app, como máximo cada 30 minutos) si hay una versión más nueva. */
    private fun checkUpdate() {
        latestBuild = prefs.getLong("latest", 0L)
        val last = prefs.getLong("checkedAt", 0L)
        if (System.currentTimeMillis() - last < 30 * 60_000L) return
        io.execute {
            try {
                val c = URL(REPO_API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "RadioRecuerdosRetro-Android")
                val body = if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
                c.disconnect()
                val tag = body?.let { JSONObject(it).optString("tag_name", "") } ?: ""
                // Las versiones se publican como v1.N, donde N es el número de compilación.
                val n = Regex("""v?\d+\.(\d+)""").find(tag)?.groupValues?.get(1)?.toLongOrNull()
                if (n != null) {
                    prefs.edit().putLong("latest", n).putLong("checkedAt", System.currentTimeMillis()).apply()
                    runOnUiThread { latestBuild = n; pushUpdate() }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Muestra el aviso solo si hay una versión más nueva que la instalada y no se cerró en las últimas 24 h. */
    private fun pushUpdate() {
        val installed = installedBuild()
        val dismissedFor = prefs.getLong("dismissedFor", 0L)
        val dismissedAt = prefs.getLong("dismissedAt", 0L)
        val snoozed = dismissedFor == latestBuild && System.currentTimeMillis() - dismissedAt < 24 * 3600_000L
        val show = latestBuild > installed && installed > 0 && !snoozed
        js("window.onUpdate && window.onUpdate($show)")
    }

    // ---------- Compartir con imagen ----------

    private fun shareText(): String {
        val m = controller?.mediaMetadata
        val title = m?.title?.toString()?.trim().orEmpty()
        val artist = m?.artist?.toString()?.trim().orEmpty()
        val what = when {
            title.isEmpty() || title == STATION -> STATION
            artist.isEmpty() || artist == RadioService.SLOGAN -> title
            else -> "$title – $artist"
        }
        return "🎶 Estoy escuchando *$what*\n📻 en $STATION\nEscúchala tú también: $WEB"
    }

    private fun coverBitmap(): Bitmap? {
        val art = controller?.mediaMetadata?.artworkUri?.toString()
        if (!art.isNullOrBlank() && art.startsWith("http")) {
            try {
                val c = URL(art).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val b = c.inputStream.use { BitmapFactory.decodeStream(it) }
                c.disconnect()
                if (b != null) return b
            } catch (_: Exception) {
            }
        }
        return try { BitmapFactory.decodeResource(resources, R.drawable.cover_default) } catch (_: Exception) { null }
    }

    private fun shareWithImage() {
        log("compartir_tema")
        val text = shareText()
        io.execute {
            val uri = try {
                val bmp = coverBitmap() ?: throw IllegalStateException()
                val dir = File(cacheDir, "compartir").apply { mkdirs() }
                val f = File(dir, "radio-recuerdos-retro.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                FileProvider.getUriForFile(this, "$packageName.archivos", f)
            } catch (_: Exception) { null }
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (uri != null) {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        type = "text/plain"
                    }
                }
                try { startActivity(Intent.createChooser(send, "Compartir")) } catch (_: Exception) {}
            }
        }
    }

    // ---------- Actualizar dentro de la app ----------

    private var downloading = false
    private var pendingApk: File? = null

    private fun updState(state: String, pct: Int = 0) {
        js("window.onUpdState && window.onUpdState('$state', $pct)")
    }

    /** Descarga la última versión con barra de progreso y abre el instalador de Android. */
    private fun downloadUpdate() {
        if (downloading) return
        downloading = true
        updState("progress", 0)
        io.execute {
            val dir = File(cacheDir, "actualizacion").apply { mkdirs() }
            val f = File(dir, "radio-recuerdos-retro.apk")
            val ok = try {
                if (f.exists()) f.delete()
                val c = URL(APK_URL).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "RadioRecuerdosRetro-Android")
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                var done = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread { updState("progress", pct) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                total <= 0 || done == total
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                downloading = false
                if (ok && f.length() > 100_000) {
                    pendingApk = f
                    updState("ready", 100)
                    installPending()
                } else {
                    updState("error")
                }
            }
        }
    }

    private fun installPending() {
        val f = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            // Primera vez: Android pide autorizar que esta app instale sus actualizaciones.
            updState("permission")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            updState("installing", 100)
        } catch (_: Exception) {
            updState("error")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            js("window.toastMsg && window.toastMsg('NO HAY NAVEGADOR EN ESTE EQUIPO')")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pushNotif()
    }

    private fun pushNotif() {
        val on = NotificationManagerCompat.from(this).areNotificationsEnabled()
        js("window.onNotif && window.onNotif($on)")
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = pushState()
            })
            if (pendingPlay) {
                pendingPlay = false
                c.play()
            }
            pushState()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        pushState()
        pushVolume()
        pushNotif()
        checkUpdate()
        // Al volver de la pantalla de permiso, seguir con la instalación.
        if (pendingApk != null && !downloading && Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
            installPending()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) pushVolume()
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    private fun js(code: String) {
        if (pageReady) web.evaluateJavascript(code, null)
    }

    private fun pushState() {
        val c = controller ?: return
        val m: MediaMetadata = c.mediaMetadata
        val o = JSONObject()
        o.put("connected", true)
        o.put("playing", c.isPlaying)
        o.put("wants", c.playWhenReady)
        o.put("buffering", c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        o.put("idle", c.playbackState == Player.STATE_IDLE)
        o.put("error", c.playerError != null)
        o.put("title", m.title?.toString() ?: "")
        o.put("artist", m.artist?.toString() ?: "")
        o.put("art", m.artworkUri?.toString() ?: "")
        o.put("sleepEnd", m.extras?.getLong(RadioService.EXTRA_SLEEP_END, 0L) ?: 0L)
        js("window.onState && window.onState($o)")
    }

    private fun pushVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        js("window.onVolume && window.onVolume(${cur.toFloat() / max})")
    }

    inner class Bridge {
        @JavascriptInterface
        fun play() = runOnUiThread {
            val c = controller
            if (c == null) { pendingPlay = true } else { c.play() }
        }

        @JavascriptInterface
        fun pause() = runOnUiThread { controller?.pause() }

        @JavascriptInterface
        fun toggle() = runOnUiThread {
            val c = controller
            if (c == null) {
                pendingPlay = !pendingPlay
            } else if (c.playWhenReady) {
                c.pause()
            } else {
                c.play()
            }
        }

        @JavascriptInterface
        fun stop() = runOnUiThread {
            controller?.sendCustomCommand(RadioService.CMD_STOP, Bundle.EMPTY)
        }

        @JavascriptInterface
        fun sleep(minutes: Int) = runOnUiThread {
            log("temporizador", Bundle().apply { putLong("minutos", minutes.toLong()) })
            controller?.sendCustomCommand(RadioService.CMD_SLEEP, Bundle().apply { putInt("min", minutes) })
        }

        @JavascriptInterface
        fun setVolume(level: Float) = runOnUiThread {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = Math.round(level.coerceIn(0f, 1f) * max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }

        @JavascriptInterface
        fun refresh() = runOnUiThread {
            pushState()
            pushVolume()
        }

        @JavascriptInterface
        fun openNotifSettings() = runOnUiThread {
            log("activar_avisos")
            val i = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            try { startActivity(i) } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        @JavascriptInterface
        fun shareNow() = runOnUiThread { shareWithImage() }

        @JavascriptInterface
        fun openWeb() = runOnUiThread {
            log("abrir_web")
            openUrl(WEB)
        }

        @JavascriptInterface
        fun update() = runOnUiThread {
            log("actualizar_app")
            if (pendingApk != null && !downloading) installPending() else downloadUpdate()
        }

        @JavascriptInterface
        fun dismissUpdate() = runOnUiThread {
            prefs.edit().putLong("dismissedFor", latestBuild).putLong("dismissedAt", System.currentTimeMillis()).apply()
            pushUpdate()
        }

        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            log("compartir_tema")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "Compartir"))
        }
    }
}
