package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NotificationHelper {

    const val CHANNEL_ID = "esp_admin_channel_v2"
    const val CHANNEL_NAME = "Esp TopUp Notifications"
    const val SERVICE_CHANNEL_ID = "esp_ongoing_service_channel"
    const val SERVICE_CHANNEL_NAME = "Esp TopUp Notice Service"
    const val DEFAULT_LOGO_URL = "https://i.ibb.co.com/yB4XVFJ3/1000020023.jpg"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for Esp TopUp"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)

            val serviceChannel = NotificationChannel(SERVICE_CHANNEL_ID, SERVICE_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Esp TopUp Notice Background Service"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    fun buildOngoingServiceNotification(context: Context): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags)

        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle("Esp TopUp notice")
            .setContentText("")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSound(null)
            .build()
    }

    fun showNotification(
        context: Context,
        title: String?,
        body: String?,
        imageUrl: String? = DEFAULT_LOGO_URL
    ) {
        val safeTitle = if (!title.isNullOrEmpty()) title else "Esp TopUp"
        val safeBody = body ?: ""
        val targetUrl = if (!imageUrl.isNullOrEmpty()) imageUrl else DEFAULT_LOGO_URL

        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = downloadBitmap(targetUrl)

            withContext(Dispatchers.Main) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }

                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    System.currentTimeMillis().toInt(),
                    intent,
                    pendingIntentFlags
                )

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_small)
                    .setColor(0xFFE53935.toInt()) // Red accent color for notification icon
                    .setColorized(true)
                    .setContentTitle(safeTitle)
                    .setContentText(safeBody)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingIntent)

                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
                } else {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
                }

                try {
                    val notificationManager = NotificationManagerCompat.from(context)
                    notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
