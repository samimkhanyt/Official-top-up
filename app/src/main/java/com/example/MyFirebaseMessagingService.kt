package com.example

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("MyFCMService", "Refreshed FCM token: $token")

        FirebaseInitHelper.ensureInitialized(this)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()

        val savedEmail = prefs.getString("user_email", "")?.lowercase()?.trim() ?: ""
        val savedUid = prefs.getString("user_uid", "")?.lowercase()?.trim() ?: ""

        try {
            val db = FirebaseDatabase.getInstance("https://samim-firebase-default-rtdb.firebaseio.com")
            val tokenData = HashMap<String, Any>()
            tokenData["token"] = token
            tokenData["email"] = savedEmail
            tokenData["uid"] = savedUid
            tokenData["updatedAt"] = System.currentTimeMillis()

            val sanitizedToken = token.replace(Regex("[.#$\\[\\]]"), "_")
            db.getReference("fcm_tokens").child(sanitizedToken).setValue(tokenData)

            if (savedEmail.isNotEmpty()) {
                val emailKey = savedEmail.replace(Regex("[.#$\\[\\]]"), "_")
                db.getReference("user_tokens").child(emailKey).setValue(tokenData)
            }
            if (savedUid.isNotEmpty()) {
                db.getReference("users").child(savedUid).child("fcmToken").setValue(token)
            }
        } catch (e: Exception) {
            Log.e("MyFCMService", "Error updating token in RTDB: ${e.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        FirebaseInitHelper.ensureInitialized(this)

        Log.d("MyFCMService", "FCM Message Received from: ${remoteMessage.from}")

        var title: String? = null
        var body: String? = null
        var imageUrl: String? = null

        remoteMessage.notification?.let {
            title = it.title
            body = it.body
            imageUrl = it.imageUrl?.toString()
        }

        if (remoteMessage.data.isNotEmpty()) {
            if (title.isNullOrEmpty()) {
                title = remoteMessage.data["title"] ?: remoteMessage.data["name"]
            }
            if (body.isNullOrEmpty()) {
                body = remoteMessage.data["body"] ?: remoteMessage.data["message"] ?: remoteMessage.data["text"]
            }
            if (imageUrl.isNullOrEmpty()) {
                imageUrl = remoteMessage.data["imageUrl"] ?: remoteMessage.data["image"] ?: remoteMessage.data["logoUrl"]
            }
        }

        val targetEmail = remoteMessage.data["targetEmail"] ?: remoteMessage.data["target"]
        if (!targetEmail.isNullOrEmpty() && targetEmail != "all" && targetEmail.lowercase() != "all") {
            if (!isEmailForThisUser(targetEmail)) {
                Log.d("MyFCMService", "Message targeted for $targetEmail, skipping for current user.")
                return
            }
        }

        val safeTitle = title ?: "Esp TopUp"
        val safeBody = body ?: ""
        val msgId = remoteMessage.messageId ?: ""
        val uniqueKey = if (msgId.isNotEmpty()) "fcm_$msgId" else "fcm_${safeTitle.hashCode()}_${safeBody.hashCode()}"

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val rawList = prefs.getString("processed_notif_keys_str", "") ?: ""
        val keysSet = rawList.split(",").toSet()

        if (keysSet.contains(uniqueKey)) {
            Log.d("MyFCMService", "Notification $uniqueKey already processed, skipping.")
            return
        }

        var keys = rawList.split(",").filter { it.isNotEmpty() }.toMutableList()
        if (!keys.contains(uniqueKey)) keys.add(uniqueKey)
        if (keys.size > 1000) keys = keys.takeLast(600).toMutableList()
        prefs.edit().putString("processed_notif_keys_str", keys.joinToString(",")).apply()

        NotificationHelper.createNotificationChannel(applicationContext)

        NotificationHelper.showNotification(
            context = applicationContext,
            title = safeTitle,
            body = safeBody,
            imageUrl = imageUrl ?: NotificationHelper.DEFAULT_LOGO_URL
        )
    }

    private fun isEmailForThisUser(targetEmail: String): Boolean {
        try {
            val cleanTarget = targetEmail.lowercase().trim()
            if (cleanTarget.isEmpty() || cleanTarget == "all") return true

            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedEmail = prefs.getString("user_email", "")?.lowercase()?.trim() ?: ""
            val savedUid = prefs.getString("user_uid", "")?.lowercase()?.trim() ?: ""

            if (savedEmail.isEmpty() && savedUid.isEmpty()) {
                return false
            }

            if (savedEmail.isNotEmpty()) {
                if (savedEmail == cleanTarget || savedEmail.contains(cleanTarget) || cleanTarget.contains(savedEmail)) {
                    return true
                }
            }

            if (savedUid.isNotEmpty() && (savedUid == cleanTarget || cleanTarget == savedUid)) {
                return true
            }
        } catch (e: Exception) {
            Log.e("MyFCMService", "Error in target check: ${e.message}")
        }
        return false
    }
}
