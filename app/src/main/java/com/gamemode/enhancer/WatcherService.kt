package com.gamemode.enhancer

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager

class WatcherService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var activeGame: String? = null
    private var lastFg: String? = null
    private var lastGameSeen = 0L
    private var startedAt = 0L
    private val prefs by lazy { getSharedPreferences("gm", Context.MODE_PRIVATE) }

    private val poll = object : Runnable {
        override fun run() {
            try { check() } catch (_: Exception) {}
            // Lock চালু থাকলে দ্রুত (০.৩ সেকেন্ড), নইলে ধীরে
            val fast = activeGame != null && prefs.getBoolean("lock", false)
            handler.postDelayed(this, if (fast) 300L else 1500L)
        }
    }

    companion object {
        fun hasUsage(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("gw", "Auto Game Mode", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "gw")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Auto Game Mode চালু আছে")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun foreground(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val win = if (lastFg == null) 60000L else 4000L
        val ev = usm.queryEvents(now - win, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
        }
        return pkg
    }

    /** call, permission dialog ইত্যাদির সময় Lock game টেনে আনবে না। */
    private fun isExempt(pkg: String): Boolean {
        val dialer = try {
            getSystemService(TelecomManager::class.java).defaultDialerPackage
        } catch (_: Exception) { null }
        return pkg == dialer ||
            pkg == "com.android.systemui" ||
            pkg.contains("incallui") ||
            pkg.contains("dialer") ||
            pkg.contains("telecom") ||
            pkg.contains("permissioncontroller")
    }

    private fun startOverlay(pkg: String, now: Long) {
        activeGame = pkg
        startedAt = now
        startForegroundService(Intent(this, OverlayService::class.java).putExtra("pkg", pkg))
    }

    private fun check() {
        if (!hasUsage(this)) return
        val now = SystemClock.elapsedRealtime()

        // Overlay নিজে (✖ দিয়ে) বন্ধ হয়ে গেলে ভুলে যাও
        if (activeGame != null && !OverlayService.running && now - startedAt > 3000) activeGame = null

        foreground()?.let { lastFg = it }
        val fg = lastFg ?: return

        if (GameUtil.isGame(this, fg)) {
            lastGameSeen = now
            if (Settings.canDrawOverlays(this) && !OverlayService.dismissed) {
                if (activeGame == null || activeGame != fg) startOverlay(fg, now)
            }
        } else {
            OverlayService.dismissed = false
            val game = activeGame ?: return
            if (prefs.getBoolean("lock", false)) {
                // 🔒 Lock: game ছেড়ে গেলে ফেরত আনো
                lastGameSeen = now
                if (!isExempt(fg)) {
                    packageManager.getLaunchIntentForPackage(game)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(it)
                    }
                }
            } else if (now - lastGameSeen > 5000) {
                stopService(Intent(this, OverlayService::class.java))
                activeGame = null
            }
        }
    }
}
