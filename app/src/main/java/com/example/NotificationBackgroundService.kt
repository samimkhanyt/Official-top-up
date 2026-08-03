package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationBackgroundService : Service() {

    private var broadcastListener: FirebaseBroadcastListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("NotifService", "NotificationBackgroundService onCreate")

        try {
            FirebaseInitHelper.ensureInitialized(applicationContext)
            NotificationHelper.createNotificationChannel(applicationContext)
            
            val ongoingNotification = NotificationHelper.buildOngoingServiceNotification(applicationContext)
            startForeground(1001, ongoingNotification)

            broadcastListener = FirebaseBroadcastListener(applicationContext)
            broadcastListener?.startListening()
        } catch (e: Throwable) {
            Log.e("NotifService", "Error starting listener in service: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NotifService", "NotificationBackgroundService onStartCommand")
        try {
            val ongoingNotification = NotificationHelper.buildOngoingServiceNotification(applicationContext)
            startForeground(1001, ongoingNotification)
        } catch (e: Throwable) {
            Log.e("NotifService", "Error promoting to foreground in onStartCommand: ${e.message}")
        }

        if (broadcastListener == null) {
            try {
                broadcastListener = FirebaseBroadcastListener(applicationContext)
                broadcastListener?.startListening()
            } catch (e: Exception) {
                Log.e("NotifService", "Error in onStartCommand: ${e.message}")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun startService(context: Context) {
            try {
                val intent = Intent(context, NotificationBackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e("NotifService", "Failed to start NotificationBackgroundService: ${e.message}")
            }
        }
    }
}
