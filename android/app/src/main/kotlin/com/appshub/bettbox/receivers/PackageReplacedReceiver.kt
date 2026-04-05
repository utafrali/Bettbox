package com.appshub.bettbox.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class PackageReplacedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        runCatching {
            Log.d(TAG, "App updated, executing state cleanup.")
            context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
                .edit()
                .remove("stop_lock_ts")
                .putBoolean("is_vpn_running", false) 
                .apply()
            if (Build.VERSION.SDK_INT >= 36) { 
                android.net.VpnService.prepare(context)
                android.net.VpnService.prepare(context)
            } 
        }.onFailure { 
            Log.e(TAG, "Reset failed", it) 
        }.also {
            pending.finish()
        }
    }
}