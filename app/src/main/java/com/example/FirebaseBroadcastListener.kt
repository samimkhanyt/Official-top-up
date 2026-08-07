package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FirebaseBroadcastListener(private val context: Context) {

    companion object {
        @Volatile
        private var isAlreadyListening = false
        private val existingLiveKeys = mutableSetOf<String>()
        private val existingBroadcastKeys = mutableSetOf<String>()
        private var listenerStartTime = 0L
    }

    private var isLiveFirstLoad = true
    private var isBroadcastFirstLoad = true

    fun startListening() {
        if (isAlreadyListening) {
            Log.d("BroadcastListener", "FirebaseBroadcastListener is already listening, skipping duplicate.")
            return
        }
        isAlreadyListening = true
        listenerStartTime = System.currentTimeMillis()

        try {
            FirebaseInitHelper.ensureInitialized(context)

            val database = try {
                FirebaseDatabase.getInstance("https://samim-firebase-default-rtdb.firebaseio.com")
            } catch (e: Throwable) {
                try {
                    FirebaseDatabase.getInstance()
                } catch (e2: Throwable) {
                    Log.e("BroadcastListener", "Failed to get FirebaseDatabase instance: ${e2.message}")
                    null
                }
            } ?: return

            // 1. Listen to live_notifications path (Main Notification Channel from Admin)
            val liveRef = database.getReference("live_notifications")

            // First load snapshot to pre-mark all existing notifications as processed
            liveRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            val uniqueKey = "live_$key"
                            existingLiveKeys.add(key)
                            existingLiveKeys.add(uniqueKey)
                            val title = child.child("title").getValue(String::class.java) ?: ""
                            val message = child.child("message").getValue(String::class.java)
                                ?: child.child("body").getValue(String::class.java)
                                ?: ""
                            markNotificationAsProcessed(uniqueKey, title, message)
                        }
                    } catch (e: Exception) {
                        Log.e("BroadcastListener", "Error pre-marking live notifications: ${e.message}")
                    } finally {
                        isLiveFirstLoad = false
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    isLiveFirstLoad = false
                }
            })

            liveRef.addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val key = snapshot.key ?: ""
                    if (isLiveFirstLoad || existingLiveKeys.contains(key) || existingLiveKeys.contains("live_$key")) {
                        if (key.isNotEmpty()) {
                            val title = snapshot.child("title").getValue(String::class.java) ?: ""
                            val message = snapshot.child("message").getValue(String::class.java)
                                ?: snapshot.child("body").getValue(String::class.java)
                                ?: ""
                            markNotificationAsProcessed("live_$key", title, message)
                        }
                        return
                    }
                    processLiveNotification(snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    processLiveNotification(snapshot)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e("BroadcastListener", "Live notification error: ${error.message}")
                }
            })

            // 2. Listen to notifications/broadcast path
            val broadcastRef = database.getReference("notifications/broadcast")

            broadcastRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            val uniqueKey = "bcast_$key"
                            existingBroadcastKeys.add(key)
                            existingBroadcastKeys.add(uniqueKey)
                            val title = child.child("title").getValue(String::class.java)
                                ?: child.child("name").getValue(String::class.java) ?: ""
                            val body = child.child("body").getValue(String::class.java)
                                ?: child.child("message").getValue(String::class.java)
                                ?: child.child("text").getValue(String::class.java) ?: ""
                            markNotificationAsProcessed(uniqueKey, title, body)
                        }
                    } catch (e: Exception) {
                        Log.e("BroadcastListener", "Error pre-marking broadcast notifications: ${e.message}")
                    } finally {
                        isBroadcastFirstLoad = false
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    isBroadcastFirstLoad = false
                }
            })

            broadcastRef.addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val key = snapshot.key ?: ""
                    if (isBroadcastFirstLoad || existingBroadcastKeys.contains(key) || existingBroadcastKeys.contains("bcast_$key")) {
                        if (key.isNotEmpty()) {
                            val title = snapshot.child("title").getValue(String::class.java)
                                ?: snapshot.child("name").getValue(String::class.java) ?: ""
                            val body = snapshot.child("body").getValue(String::class.java)
                                ?: snapshot.child("message").getValue(String::class.java)
                                ?: snapshot.child("text").getValue(String::class.java) ?: ""
                            markNotificationAsProcessed("bcast_$key", title, body)
                        }
                        return
                    }
                    processDataSnapshot(snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    processDataSnapshot(snapshot)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e("BroadcastListener", "Database error: ${error.message}")
                }
            })

        } catch (t: Throwable) {
            Log.e("BroadcastListener", "Error starting Firebase broadcast listener: ${t.message}", t)
        }
    }

    private fun isNotificationAlreadyProcessed(uniqueKey: String, title: String = "", body: String = ""): Boolean {
        if (uniqueKey.isEmpty()) return false
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val rawList = prefs.getString("processed_notif_keys_str", "") ?: ""
        val keys = rawList.split(",").toSet()

        if (keys.contains(uniqueKey)) return true
        if (existingLiveKeys.contains(uniqueKey) || existingBroadcastKeys.contains(uniqueKey)) return true

        return false
    }

    private fun markNotificationAsProcessed(uniqueKey: String, title: String = "", body: String = "") {
        if (uniqueKey.isEmpty()) return
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val rawList = prefs.getString("processed_notif_keys_str", "") ?: ""
        var keys = rawList.split(",").filter { it.isNotEmpty() }.toMutableList()

        if (!keys.contains(uniqueKey)) {
            keys.add(uniqueKey)
            if (keys.size > 1000) {
                keys = keys.takeLast(600).toMutableList()
            }
            prefs.edit().putString("processed_notif_keys_str", keys.joinToString(",")).apply()
        }
    }

    private fun processLiveNotification(snapshot: DataSnapshot) {
        try {
            val key = snapshot.key ?: ""
            if (key.isEmpty()) return

            val title = snapshot.child("title").getValue(String::class.java)
                ?: snapshot.child("name").getValue(String::class.java)
                ?: "Esp TopUp"
            val message = snapshot.child("message").getValue(String::class.java)
                ?: snapshot.child("body").getValue(String::class.java)
                ?: snapshot.child("text").getValue(String::class.java)
                ?: ""

            val uniqueKey = "live_$key"
            if (isNotificationAlreadyProcessed(uniqueKey, title, message)) {
                Log.d("BroadcastListener", "Live notification $uniqueKey already processed, skipping.")
                return
            }

            val logoUrl = snapshot.child("logoUrl").getValue(String::class.java)
                ?: snapshot.child("image").getValue(String::class.java)
                ?: snapshot.child("imageUrl").getValue(String::class.java)
                ?: NotificationHelper.DEFAULT_LOGO_URL
            val target = snapshot.child("target").getValue(String::class.java) ?: "all"
            val targetEmail = snapshot.child("targetEmail").getValue(String::class.java)?.lowercase()?.trim() ?: ""
            val rawTime = snapshot.child("clientTimestamp").getValue(Long::class.java)
                ?: snapshot.child("timestamp").getValue(Long::class.java)
                ?: snapshot.child("time").getValue(Long::class.java)
                ?: 0L

            if (message.isEmpty()) return

            val timestampMs = if (rawTime in 1..9999999999L) rawTime * 1000L else rawTime
            val currentTime = System.currentTimeMillis()
            val isRecent = (timestampMs == 0L) || (Math.abs(currentTime - timestampMs) < 24 * 60 * 60 * 1000L)
            val isTargetUser = (target == "all" || targetEmail.isEmpty() || targetEmail == "all" || isEmailForThisUser(targetEmail))

            // Mark processed immediately so it's never processed again
            markNotificationAsProcessed(uniqueKey, title, message)

            if (isRecent && isTargetUser) {
                NotificationHelper.showNotification(
                    context = context,
                    title = title,
                    body = message,
                    imageUrl = logoUrl
                )
            }
        } catch (e: Exception) {
            Log.e("BroadcastListener", "Error processing live notification: ${e.message}")
        }
    }

    private fun isEmailForThisUser(targetEmail: String): Boolean {
        try {
            val cleanTarget = targetEmail.lowercase().trim()
            if (cleanTarget.isEmpty() || cleanTarget == "all") return true

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedEmail = prefs.getString("user_email", "")?.lowercase()?.trim() ?: ""
            val savedUid = prefs.getString("user_uid", "")?.lowercase()?.trim() ?: ""

            // If user is NOT logged in in the app, specific targeted notifications MUST NOT match!
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
            Log.e("BroadcastListener", "Error checking target email: ${e.message}")
        }
        return false
    }

    private fun processDataSnapshot(snapshot: DataSnapshot) {
        try {
            val key = snapshot.key ?: ""
            if (key.isEmpty()) return

            val title = snapshot.child("title").getValue(String::class.java)
                ?: snapshot.child("name").getValue(String::class.java)
                ?: "Esp TopUp"

            val body = snapshot.child("body").getValue(String::class.java)
                ?: snapshot.child("message").getValue(String::class.java)
                ?: snapshot.child("text").getValue(String::class.java)
                ?: snapshot.getValue(String::class.java)
                ?: ""

            val uniqueKey = "bcast_$key"
            if (isNotificationAlreadyProcessed(uniqueKey, title, body)) {
                Log.d("BroadcastListener", "Broadcast notification $uniqueKey already processed, skipping.")
                return
            }

            val imageUrl = snapshot.child("image").getValue(String::class.java)
                ?: snapshot.child("imageUrl").getValue(String::class.java)
                ?: snapshot.child("logoUrl").getValue(String::class.java)
                ?: NotificationHelper.DEFAULT_LOGO_URL

            if (body.isEmpty()) return

            markNotificationAsProcessed(uniqueKey, title, body)

            NotificationHelper.showNotification(
                context = context,
                title = title,
                body = body,
                imageUrl = imageUrl
            )
        } catch (e: Exception) {
            Log.e("BroadcastListener", "Error processing DataSnapshot: ${e.message}")
        }
    }
}
