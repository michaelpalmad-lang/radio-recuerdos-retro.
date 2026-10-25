package cl.radiorecuerdos.retro

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Servicio de reproducción de Radio Recuerdos Retro.
 * - Reproduce la transmisión en vivo en segundo plano, con controles en la notificación,
 *   la pantalla de bloqueo, auriculares Bluetooth y Android Auto.
 * - Lee el tema que suena (metadatos ICY del stream y el panel de la radio) y busca la carátula.
 */
class RadioService : MediaSessionService() {

    companion object {
        const val STREAM = "https://sonic.portalfoxmix.cl/8226/stream"
        const val STATION = "Radio Recuerdos Retro"
        const val SLOGAN = "En vivo · Clásicos de los 80 y 90"

        // Fuentes de información del tema actual (panel SonicPanel / Shoutcast del servidor).
        // Mismo servidor que usa el reproductor de recuerdosretro.com (Shoutcast 2, puerto 7175, id 1).
        val INFO_URLS = listOf(
            "https://sonic.portalfoxmix.cl:7175/stats?sid=1&json=1",
            "https://sonic.portalfoxmix.cl/cp/get_info.php?p=8226",
            "https://sonic.portalfoxmix.cl:7175/currentsong?sid=1",
            "https://sonic.portalfoxmix.cl/8226/stats?json=1"
        )

        const val ACT_STOP = "rrr.STOP"
        const val ACT_SLEEP = "rrr.SLEEP"
        val CMD_STOP = SessionCommand(ACT_STOP, Bundle.EMPTY)
        val CMD_SLEEP = SessionCommand(ACT_SLEEP, Bundle.EMPTY)
        const val EXTRA_SLEEP_END = "sleepEnd"
    }

    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    private var rawTitle = ""
    private var title: String? = null
    private var artist: String? = null
    private var artUrl: String? = null
    private var artFromPanel = false
    private var sleepEnd = 0L
    private var pausedAt = 0L
    private var retries = 0
    private var defaultArt: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        defaultArt = try {
            resources.openRawResource(R.drawable.cover_default).use { it.readBytes() }
        } catch (e: Exception) { null }

        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.setMediaItem(buildItem())

        exo.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is IcyInfo && !e.title.isNullOrBlank()) onTrack(e.title!!, null)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) pausedAt = SystemClock.elapsedRealtime()
                else pollNow()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retries = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // Si se corta la señal, reintenta solo mientras el usuario quiera escuchar.
                if (retries < 20) {
                    retries++
                    main.postDelayed({
                        exo.prepare()
                        exo.play()
                    }, 3000L)
                }
            }
        })

        // Para una radio en vivo: al volver de una pausa larga se reconecta a la señal actual.
        val player = object : ForwardingPlayer(exo) {
            override fun play() {
                val now = SystemClock.elapsedRealtime()
                if (exo.playbackState == Player.STATE_IDLE) {
                    exo.prepare()
                } else if (pausedAt > 0 && now - pausedAt > 15_000) {
                    exo.seekToDefaultPosition()
                }
                pausedAt = 0
                super.play()
            }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(open)
            .build()

        session?.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName("Detener")
                    .setIconResId(R.drawable.ic_stop)
                    .setSessionCommand(CMD_STOP)
                    .build()
            )
        )

        main.post(poller)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---------- Controles personalizados ----------

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(CMD_STOP)
                .add(CMD_SLEEP)
                .build()
            // Radio en vivo: sin adelantar, retroceder ni cambiar de tema.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                )
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACT_STOP -> {
                    cancelSleep()
                    exo.pause()
                    exo.stop()
                }
                ACT_SLEEP -> setSleep(args.getInt("min", 0))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // ---------- Temporizador de apagado ----------

    private val sleepTask = Runnable {
        sleepEnd = 0
        exo.pause()
        refreshItem()
    }

    private fun setSleep(min: Int) {
        main.removeCallbacks(sleepTask)
        if (min > 0) {
            sleepEnd = System.currentTimeMillis() + min * 60_000L
            main.postDelayed(sleepTask, min * 60_000L)
        } else {
            sleepEnd = 0
        }
        refreshItem()
    }

    private fun cancelSleep() = setSleep(0)

    // ---------- Tema actual y carátula ----------

    private fun buildItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title ?: STATION)
            .setArtist(artist ?: SLOGAN)
            .setAlbumTitle(STATION)
            .setStation(STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setExtras(Bundle().apply { putLong(EXTRA_SLEEP_END, sleepEnd) })
        if (artUrl != null) {
            meta.setArtworkUri(Uri.parse(artUrl))
        } else if (defaultArt != null) {
            meta.setArtworkData(defaultArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return MediaItem.Builder()
            .setMediaId("radio-recuerdos-retro")
            .setUri(STREAM)
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun refreshItem() {
        // Mismo stream, solo cambian los datos: no se corta el audio.
        if (exo.mediaItemCount > 0) exo.replaceMediaItem(0, buildItem())
        else exo.setMediaItem(buildItem())
    }

    /** Llamado con el texto "Artista - Tema" y, si el panel la entrega, la URL de la carátula. */
    private fun onTrack(raw: String, panelArt: String?) {
        val clean = raw.trim()
        if (clean.isEmpty()) return

        if (clean == rawTitle) {
            if (panelArt != null && panelArt != artUrl) {
                artUrl = panelArt
                artFromPanel = true
                refreshItem()
            }
            return
        }

        rawTitle = clean
        val parts = clean.split(" - ", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            artist = parts[0].trim()
            title = parts[1].trim()
        } else {
            artist = SLOGAN
            title = clean
        }
        artUrl = panelArt
        artFromPanel = panelArt != null
        refreshItem()

        if (panelArt == null) {
            val query = clean
            io.execute {
                val found = searchCover(query)
                main.post {
                    if (rawTitle == query && !artFromPanel && found != null) {
                        artUrl = found
                        refreshItem()
                    }
                }
            }
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            pollNow()
            main.postDelayed(this, if (exo.playWhenReady) 10_000L else 30_000L)
        }
    }

    private fun pollNow() {
        io.execute {
            val info = readPanel() ?: return@execute
            main.post { onTrack(info.first, info.second) }
        }
    }

    private fun readPanel(): Pair<String, String?>? {
        for (u in INFO_URLS) {
            try {
                val body = httpGet(u)?.trim() ?: continue
                if (body.isEmpty()) continue
                if (!body.startsWith("{")) {
                    // currentsong entrega solo el texto "Artista - Tema".
                    if (body.length < 200 && !body.startsWith("<")) return Pair(htmlDecode(body), null)
                    continue
                }
                val j = JSONObject(body)
                val t = listOf("title", "songtitle", "currentsong", "song")
                    .map { j.optString(it, "") }
                    .firstOrNull { it.isNotBlank() } ?: continue
                var art: String? = listOf("art", "artwork", "cover", "albumart", "image", "picture")
                    .map { j.optString(it, "") }
                    .firstOrNull { it.isNotBlank() }
                if (art != null) {
                    if (!art.startsWith("http")) art = URL(URL(u), art).toString()
                    val low = art.lowercase()
                    if (low.contains("nocover") || low.contains("no_cover") || low.contains("noimage") ||
                        low.contains("default")) art = null
                }
                return Pair(htmlDecode(t), art)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun searchCover(query: String): String? {
        return try {
            val term = URLEncoder.encode(query.replace(" - ", " "), "UTF-8")
            val body = httpGet("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=1")
                ?: return null
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val a = results.getJSONObject(0).optString("artworkUrl100", "")
            if (a.isBlank()) null else a.replace("100x100bb", "600x600bb")
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 7000
            c.readTimeout = 7000
            c.setRequestProperty("User-Agent", "RadioRecuerdosRetro/1.0 (Android)")
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun htmlDecode(s: String): String =
        s.replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}
