package com.gamemode.enhancer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = ctx.getSharedPreferences("gm", Context.MODE_PRIVATE)
        if (p.getBoolean("auto", true) && WatcherService.hasUsage(ctx)) {
            try {
                ctx.startForegroundService(Intent(ctx, WatcherService::class.java))
            } catch (_: Exception) {}
        }
    }
}
