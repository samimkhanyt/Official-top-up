package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    Log.d("BootReceiver", "Action received: $action")
    if (action == Intent.ACTION_BOOT_COMPLETED ||
      action == Intent.ACTION_MY_PACKAGE_REPLACED ||
      action == "com.example.CHECK_NOTIFICATIONS" ||
      action == "android.intent.action.QUICKBOOT_POWERON"
    ) {
      NotificationBackgroundService.startService(context)
    }
  }
}
