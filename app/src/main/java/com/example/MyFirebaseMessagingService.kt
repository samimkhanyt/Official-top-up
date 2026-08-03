package com.example

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title 
            ?: remoteMessage.data["title"] 
            ?: getString(R.string.app_name)

        val body = remoteMessage.notification?.body 
            ?: remoteMessage.data["body"] 
            ?: ""

        if (body.isNotEmpty()) {
            NotificationHelper.showNotification(applicationContext, title, body)
        }
    }

    companion object {
        private const val TAG = "FCMService"
    }
}
