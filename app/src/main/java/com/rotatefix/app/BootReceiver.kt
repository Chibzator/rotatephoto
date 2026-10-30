package com.rotatefix.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Restarts RotateFix after a reboot or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Prefs.enabled(context) || !Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, OrientService::class.java))
        } catch (e: Exception) {
            Log.e("RotateFix", "Could not start after boot", e)
        }
    }
}
