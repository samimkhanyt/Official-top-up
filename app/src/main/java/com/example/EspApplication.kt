package com.example

import android.app.Application
import android.util.Log

class EspApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("EspApplication", "EspApplication initialized")

        // Ensure Firebase is initialized safely as early as possible
        FirebaseInitHelper.ensureInitialized(this)

        // Global uncaught exception handler to prevent app force-close crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("EspApplication", "Uncaught exception caught on thread ${thread.name}: ${throwable.message}", throwable)
            // Prevent silent crashes on secondary threads
            if (thread.name.contains("main", ignoreCase = true)) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}

