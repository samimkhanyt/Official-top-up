package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseInitHelper {
    @Synchronized
    fun ensureInitialized(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyA3B7DZ_JeY-a33zdixKmZ6oQrU2iEVsEk")
                    .setApplicationId("1:521003542655:web:bbabcf2f24a0eaeeef774a")
                    .setProjectId("samim-firebase")
                    .setGcmSenderId("521003542655")
                    .setDatabaseUrl("https://samim-firebase-default-rtdb.firebaseio.com")
                    .build()
                FirebaseApp.initializeApp(context.applicationContext, options)
                Log.d("FirebaseInitHelper", "FirebaseApp initialized successfully")
            }
        } catch (t: Throwable) {
            Log.e("FirebaseInitHelper", "Error initializing FirebaseApp: ${t.message}", t)
        }
    }
}
