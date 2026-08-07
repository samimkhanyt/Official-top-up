package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class NotificationBackgroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d("NotifService", "NotificationBackgroundService onCreate - stopping sticky notification")
        removePersistentNotification()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NotifService", "NotificationBackgroundService onStartCommand - stopping sticky notification")
        removePersistentNotification()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun removePersistentNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.cancel(1001)
        } catch (e: Throwable) {
            Log.e("NotifService", "Error removing notification: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun startService(context: Context) {
            try {
                val intent = Intent(context, NotificationBackgroundService::class.java)
                context.stopService(intent)
            } catch (e: Throwable) {
                Log.e("NotifService", "Failed to stop NotificationBackgroundService: ${e.message}")
            }
        }
    }
}
