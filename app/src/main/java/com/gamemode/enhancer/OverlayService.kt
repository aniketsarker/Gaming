package com.gamemode.enhancer

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var dismissed = false
    }

    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null
    private var panel: LinearLayout? = null
    private var stats: TextView? = null
    private var eq: Equalizer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var gamePkg: String? = null
    private var colorOn = false
    private var soundMode = 0
    private var colorIdx = 0
    private var forceHz = true
    private var hot = false
    private var prevSaver = 0
    private var prevFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("gm", Context.MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nm = getSystemService(NotificationManager::class.java)
        startFg()
        prefs.edit().putBoolean("lock", false).apply()
        prevFilter = nm.currentInterruptionFilter
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
        addBubble()
        setForceHz(true)
        batterySaverOff()
        wifiOn()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        gamePkg = intent?.getStringExtra("pkg") ?: gamePkg
        GameUtil.cleanRam(this, gamePkg)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        prefs.edit().putBoolean("lock", false).apply()
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        try { eq?.release() } catch (_: Exception) {}
        if (colorOn) setColor(-1)
        setForceHz(false)
        restoreSaver()
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(prevFilter)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun startFg() {
        nm.createNotificationChannel(
            NotificationChannel("gm", "Game Mode", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "gm")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Game Mode চালু")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- Display / 120Hz ----------
    private fun display(): Display =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private fun hzMax(): Float = try {
        display().supportedModes.maxOf { it.refreshRate }
    } catch (_: Exception) { 120f }

    private fun applyHz(lp: WindowManager.LayoutParams) {
        try {
            val d = display()
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) {
                lp.preferredDisplayModeId = best.modeId
                lp.preferredRefreshRate = best.refreshRate
            }
        } catch (_: Exception) {}
    }

    /** on = display-র minimum refresh rate সর্বোচ্চ করো, off = আগের মান ফেরাও */
    private fun setForceHz(on: Boolean) {
        if (!Settings.System.canWrite(this)) return
        try {
            if (on) {
                if (!prefs.contains("prev_min")) {
                    prefs.edit().putFloat(
                        "prev_min",
                        Settings.System.getFloat(contentResolver, "min_refresh_rate", 0f)
                    ).apply()
                }
                Settings.System.putFloat(contentResolver, "min_refresh_rate", hzMax())
            } else if (prefs.contains("prev_min")) {
                Settings.System.putFloat(contentResolver, "min_refresh_rate", prefs.getFloat("prev_min", 0f))
                prefs.edit().remove("prev_min").apply()
            }
        } catch (_: Exception) {}
    }

    // ---------- Battery Saver ----------
    private fun batterySaverOff() {
        try {
            prevSaver = Settings.Global.getInt(contentResolver, "low_power", 0)
            if (prevSaver == 1) Settings.Global.putInt(contentResolver, "low_power", 0)
        } catch (_: Exception) {}
    }

    private fun restoreSaver() {
        try {
            if (prevSaver == 1) Settings.Global.putInt(contentResolver, "low_power", 1)
        } catch (_: Exception) {}
    }

    // ---------- Wi-Fi Low Latency ----------
    private fun wifiOn() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = w.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "gm").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    // ---------- Color ----------
    // mode: -1 = off, 12 = A, 11 = B, 13 = C
    private fun setColor(mode: Int) {
        try {
            val cr = contentResolver
            if (mode < 0) {
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", 0)
                colorOn = false
            } else {
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer", mode)
                Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", 1)
                colorOn = true
            }
        } catch (e: SecurityException) {
            toast("আগে adb দিয়ে WRITE_SECURE_SETTINGS permission দাও")
        }
    }

    // ---------- Floating bubble ----------
    private fun paintBubble() {
        bubbleBg?.setColor(if (hot) 0xCCD32F2F.toInt() else 0x99263238.toInt())
    }

    private fun addBubble() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99263238.toInt())
            setStroke(dp(1), 0x55FFFFFF)
        }
        bubbleBg = bg
        val b = TextView(this).apply {
            text = "🎮"
            textSize = 15f
            gravity = Gravity.CENTER
            background = bg
        }
        bubbleLp = WindowManager.LayoutParams(
            dp(36), dp(36),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(160)
        }
        applyHz(bubbleLp)
        b.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = bubbleLp.x; sy = bubbleLp.y
                        tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        bubbleLp.x = sx + dx
                        bubbleLp.y = sy + dy
                        wm.updateViewLayout(b, bubbleLp)
                    }
                    MotionEvent.ACTION_UP -> if (!moved) togglePanel()
                }
                return true
            }
        })
        wm.addView(b, bubbleLp)
        bubble = b
    }

    // ---------- Panel ----------
    private fun togglePanel() { if (panel == null) openPanel() else closePanel() }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        stats = null
    }

    private fun seek(maxV: Int, cur: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = maxV
        progress = cur
        val c = ColorStateList.valueOf(0xFF2979FF.toInt())
        progressTintList = c
        thumbTintList = c
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun sliderRow(icon: String, maxV: Int, cur: Int, onChange: (Int) -> Unit): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(
            TextView(this).apply { text = icon; textSize = 13f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        r.addView(seek(maxV, cur, onChange), LinearLayout.LayoutParams(0, dp(30), 1f))
        return r
    }

    private fun chip(icon: String, onClick: (TextView) -> Unit): TextView {
        val t = TextView(this).apply {
            text = icon
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x22FFFFFF)
            }
        }
        t.setOnClickListener { onClick(t) }
        return t
    }

    private fun setOn(t: TextView, on: Boolean, color: Int = 0xFF2979FF.toInt()) {
        (t.background as GradientDrawable).setColor(if (on) color else 0x22FFFFFF)
    }

    private fun openPanel() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6161B22.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        // এক লাইনের ছোট stats
        val st = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
        }
        stats = st
        root.addView(st, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateStats()

        // আইকন বাটনের সারি
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        fun add(v: View) {
            row.addView(v, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }

        // 🔒 Lock (Back/Home আটকানো)
        val lockOn = prefs.getBoolean("lock", false)
        val lk = chip(if (lockOn) "🔒" else "🔓") { t ->
            val on = !prefs.getBoolean("lock", false)
            prefs.edit().putBoolean("lock", on).apply()
            t.text = if (on) "🔒" else "🔓"
            setOn(t, on, 0xFFFF9100.toInt())
            toast(if (on) "Lock চালু: game থেকে বের হওয়া যাবে না" else "Lock বন্ধ")
        }
        setOn(lk, lockOn, 0xFFFF9100.toInt())
        add(lk)

        // 🔕 Focus
        val fc = chip("🔕") { t ->
            if (!nm.isNotificationPolicyAccessGranted) {
                toast("অ্যাপে গিয়ে DND permission দাও")
            } else {
                val wasOff = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(
                    if (wasOff) NotificationManager.INTERRUPTION_FILTER_ALARMS
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                setOn(t, wasOff)
            }
        }
        setOn(fc, nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        add(fc)

        // 🎧 Sound (Normal → Bass → Footstep)
        val sc = chip("🎧") { t ->
            soundMode = (soundMode + 1) % 3
            applyEq(soundMode)
            setOn(t, soundMode != 0)
            toast(listOf("Sound: Normal", "Sound: Bass", "Sound: Footstep")[soundMode])
        }
        setOn(sc, soundMode != 0)
        add(sc)

        // 🎨 Enemy রং (Off → A → B → C)
        val cc = chip("🎨") { t ->
            colorIdx = (colorIdx + 1) % 4
            setColor(intArrayOf(-1, 12, 11, 13)[colorIdx])
            setOn(t, colorIdx != 0)
            toast(if (colorIdx == 0) "রং: Off" else "রং: " + "ABC"[colorIdx - 1])
        }
        setOn(cc, colorIdx != 0)
        add(cc)

        // 🖥 120Hz ধরে রাখা
        val hc = chip("🖥") { t ->
            forceHz = !forceHz
            setForceHz(forceHz)
            setOn(t, forceHz)
            toast(if (forceHz) "120Hz ধরে রাখা চালু" else "120Hz ধরে রাখা বন্ধ")
        }
        setOn(hc, forceHz)
        add(hc)

        // 🧹 RAM পরিষ্কার
        add(chip("🧹") {
            val n = GameUtil.cleanRam(this@OverlayService, gamePkg)
            toast("$n টা app বন্ধ হলো")
            updateStats()
        })

        // ✖ Game Mode বন্ধ
        add(chip("✖") {
            dismissed = true
            stopSelf()
        })
        root.addView(row)

        // ☀ Brightness
        val curB = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }
        root.addView(sliderRow("☀", 255, curB) { p ->
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, max(p, 1))
            } else toast("অ্যাপে গিয়ে Brightness permission দাও")
        })

        // 🔊 Volume
        root.addView(sliderRow("🔊", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            am.getStreamVolume(AudioManager.STREAM_MUSIC)) { p ->
            am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
        })

        val dm = resources.displayMetrics
        val w = dp(272)
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleLp.x + dp(44)).coerceAtMost(dm.widthPixels - w).coerceAtLeast(0)
            y = bubbleLp.y.coerceAtMost(dm.heightPixels - dp(180)).coerceAtLeast(0)
        }
        applyHz(lp)
        wm.addView(root, lp)
        panel = root
    }

    private fun updateStats() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        // 🌡 গরম হলে বল লাল হবে, একবার সতর্কও করবে
        val nowHot = temp >= 42f
        if (nowHot != hot) {
            hot = nowHot
            paintBubble()
            if (hot) toast("🌡 ফোন গরম হচ্ছে: $temp°")
        }

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val hz = try { display().refreshRate.toInt() } catch (_: Exception) { 0 }
        stats?.let {
            it.text = "🔋${lvl * 100 / scale}%   🌡$temp°   🧠${mi.availMem / 1048576}MB   🖥${hz}Hz"
            it.setTextColor(if (hot) 0xFFFF5252.toInt() else 0xCCFFFFFF.toInt())
        }
    }

    private fun applyEq(mode: Int) {
        try {
            if (eq == null) eq = Equalizer(0, 0).also { it.setEnabled(true) }
            val e = eq!!
            val min = e.bandLevelRange[0].toInt()
            val mx = e.bandLevelRange[1].toInt()
            for (i in 0 until e.numberOfBands.toInt()) {
                val f = e.getCenterFreq(i.toShort())
                val lvl = when (mode) {
                    1 -> if (f < 250000) (mx * 0.7).toInt() else 0
                    2 -> if (f >= 2000000) (mx * 0.7).toInt() else if (f < 250000) (min * 0.3).toInt() else 0
                    else -> 0
                }
                e.setBandLevel(i.toShort(), lvl.toShort())
            }
        } catch (ex: Exception) {
            toast("এই ফোনে Equalizer সাপোর্ট করছে না")
        }
    }
}
