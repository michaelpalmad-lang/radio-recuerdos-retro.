package cl.radiorecuerdos.retro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mensajes push enviados desde la consola de Firebase (Messaging).
 * - Con la app cerrada o minimizada, Android muestra el aviso solo (canal "avisos").
 * - Con la app abierta llega aquí y se muestra igual, con imagen si el mensaje la trae.
 * - Todas las instalaciones quedan suscritas al tema "todos".
 */
class PushService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL = "avisos"
        const val TOPIC = "todos"

        /** Crea el canal de avisos y suscribe la app al tema general. Se llama al abrir la app. */
        fun setup(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    val ch = NotificationChannel(CHANNEL, "Avisos de la radio", NotificationManager.IMPORTANCE_HIGH)
                    ch.description = "Especiales, programas y novedades de Radio Recuerdos Retro"
                    ch.enableLights(true)
                    ch.lightColor = Color.parseColor("#FF2A8C")
                    nm.createNotificationChannel(ch)
                }
            }
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
            } catch (_: Exception) {
            }
        }
    }

    override fun onNewToken(token: String) {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
        } catch (_: Exception) {
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification
        val title = n?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = n?.body ?: message.data["body"] ?: return
        val imageUrl = n?.imageUrl?.toString() ?: message.data["image"]

        setup(this)
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setColor(Color.parseColor("#FF2A8C"))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)

        val img = imageUrl?.let { loadBitmap(it) }
        if (img != null) {
            b.setLargeIcon(img)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(img).setSummaryText(body))
        }

        try {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun loadBitmap(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
}
