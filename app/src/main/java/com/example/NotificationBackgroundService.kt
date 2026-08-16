package com.example

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class NotificationBackgroundService : Service() {

  companion object {
    private const val TAG = "ESP_NotifService"
    const val CHANNEL_ID = "esp_topup_channel"
    const val CHANNEL_NAME = "ESP TopUp Notifications"

    // Firebase Endpoints
    private const val FIREBASE_NOTIF_URL_1 = "https://espopup-bd-default-rtdb.firebaseio.com/pushNotification.json"
    private const val FIREBASE_NOTIF_URL_2 = "https://espopup-bd-default-rtdb.firebaseio.com/notifications.json"
    private const val FIREBASE_NOTIF_FALLBACK_1 = "https://samim-firebase-default-rtdb.firebaseio.com/pushNotification.json"
    private const val FIREBASE_NOTIF_FALLBACK_2 = "https://samim-firebase.firebaseio.com/pushNotification.json"

    fun startService(context: Context) {
      try {
        val intent = Intent(context, NotificationBackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Cannot start background notification service: ${e.message}")
      }
    }
  }

  private val serviceJob = Job()
  private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
  private lateinit var prefs: SharedPreferences

  private val okHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS, okhttp3.ConnectionSpec.COMPATIBLE_TLS, okhttp3.ConnectionSpec.CLEARTEXT))
      .build()
  }

  override fun onCreate() {
    super.onCreate()
    prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    createNotificationChannel()
    startPollingLoop()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    scheduleNextWakeup()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onTaskRemoved(rootIntent: Intent?) {
    scheduleNextWakeup()
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    serviceJob.cancel()
    scheduleNextWakeup()
    super.onDestroy()
  }

  private fun scheduleNextWakeup() {
    try {
      val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      val intent = Intent(applicationContext, BootReceiver::class.java).apply {
        action = "com.example.CHECK_NOTIFICATIONS"
      }
      val pendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        1001,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      val triggerAtMillis = SystemClock.elapsedRealtime() + (30 * 1000) // 30 seconds
      alarmManager?.set(
        AlarmManager.ELAPSED_REALTIME,
        triggerAtMillis,
        pendingIntent
      )
    } catch (e: Exception) {
      Log.w(TAG, "Alarm schedule notice: ${e.message}")
    }
  }

  private fun startPollingLoop() {
    serviceScope.launch {
      val endpoints = listOf(
        FIREBASE_NOTIF_URL_1,
        FIREBASE_NOTIF_URL_2,
        FIREBASE_NOTIF_FALLBACK_1,
        FIREBASE_NOTIF_FALLBACK_2
      )

      while (isActive) {
        for (endpoint in endpoints) {
          try {
            val request = Request.Builder().url(endpoint).build()
            okHttpClient.newCall(request).execute().use { response ->
              if (response.isSuccessful) {
                val body = response.body?.string()?.trim()
                if (!body.isNullOrBlank() && body != "null") {
                  parseAndTriggerNotification(body)
                  return@use
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Polling notice: ${e.message}")
          }
        }
        // Check every 6 seconds in background
        delay(6000)
      }
    }
  }

  private suspend fun parseAndTriggerNotification(rawJson: String) {
    try {
      val json = JSONObject(rawJson)
      val notifId = json.optString("id", "")
        .ifBlank { json.optString("timestamp", "") }
        .ifBlank { json.optString("title", "") + "_" + json.optString("message", "") }

      if (notifId.isBlank()) return

      val lastSeenId = prefs.getString(MainActivity.LAST_NOTIF_KEY, "")
      if (notifId == lastSeenId) {
        return
      }

      val title = json.optString("title", "ESP TopUp").ifBlank { "ESP TopUp" }
      val message = json.optString("message", "")
        .ifBlank { json.optString("body", "") }
        .ifBlank { json.optString("text", "") }

      if (message.isBlank()) return

      val imageUrl = json.optString("imageUrl", "")
        .ifBlank { json.optString("image", "") }
        .ifBlank { json.optString("logo", "") }
        .ifBlank { json.optString("icon", "") }

      val targetUrl = json.optString("url", "")
        .ifBlank { json.optString("link", "") }

      // Save as seen
      prefs.edit().putString(MainActivity.LAST_NOTIF_KEY, notifId).apply()

      var bannerBitmap: Bitmap? = null
      if (imageUrl.isNotBlank() && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
        bannerBitmap = downloadBitmap(imageUrl)
      }

      withContext(Dispatchers.Main) {
        showSystemNotification(
          title = title,
          message = message,
          targetUrl = targetUrl,
          bitmap = bannerBitmap
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Notification parsing error: ${e.message}")
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()

      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "Live admin notifications and alerts from ESP TopUp"
        enableLights(true)
        lightColor = Color.parseColor("#FF5722")
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 250, 150, 250)
        setSound(soundUri, audioAttributes)
        setShowBadge(true)
        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
      }

      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun showSystemNotification(
    title: String,
    message: String,
    targetUrl: String?,
    bitmap: Bitmap?
  ) {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      if (!targetUrl.isNullOrBlank()) {
        putExtra("target_url", targetUrl)
      }
    }

    val pendingIntent = PendingIntent.getActivity(
      this,
      (System.currentTimeMillis() % 100000).toInt(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification_t)
      .setContentTitle(title)
      .setContentText(message)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .setSound(soundUri)
      .setVibrate(longArrayOf(0, 250, 150, 250))
      .setContentIntent(pendingIntent)
      .setColor(Color.parseColor("#FF5722"))

    if (bitmap != null) {
      builder.setLargeIcon(bitmap)
      builder.setStyle(
        NotificationCompat.BigPictureStyle()
          .bigPicture(bitmap)
          .setSummaryText(message)
      )
    } else {
      builder.setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(message)
      )
    }

    try {
      val notificationManager = NotificationManagerCompat.from(this)
      notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    } catch (e: Exception) {
      Log.e(TAG, "Error displaying background notification", e)
    }
  }

  private fun downloadBitmap(urlString: String): Bitmap? {
    return try {
      val url = URL(urlString)
      val connection = url.openConnection() as HttpURLConnection
      connection.doInput = true
      connection.connectTimeout = 7000
      connection.readTimeout = 7000
      connection.connect()
      val input: InputStream = connection.inputStream
      BitmapFactory.decodeStream(input)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to download image: $urlString", e)
      null
    }
  }
}
