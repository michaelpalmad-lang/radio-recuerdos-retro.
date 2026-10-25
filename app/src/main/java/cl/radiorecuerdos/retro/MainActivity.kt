package cl.radiorecuerdos.retro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.analytics.FirebaseAnalytics
import org.json.JSONObject

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
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) pushVolume()
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
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
