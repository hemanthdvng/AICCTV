package com.securecam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts [AlertService] after device reboot or app update,
 * so offline notifications work without requiring the app to be opened.
 *
 * Triggered by:
 *  - android.intent.action.BOOT_COMPLETED  (normal reboot)
 *  - android.intent.action.LOCKED_BOOT_COMPLETED (direct-boot on Android 7+)
 *  - android.intent.action.MY_PACKAGE_REPLACED (app update)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val relevantActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (intent.action !in relevantActions) return

        val serviceIntent = Intent(context, AlertService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
