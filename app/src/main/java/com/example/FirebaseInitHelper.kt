package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

object FirebaseInitHelper {
    private const val TAG = "FirebaseInitHelper"

    fun initialize(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
                Log.d(TAG, "FirebaseApp initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseApp", e)
        }
    }
}
