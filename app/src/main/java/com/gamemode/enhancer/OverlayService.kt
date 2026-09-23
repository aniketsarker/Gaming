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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.audiofx.Equalizer
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var stats: TextView? = null
    private var eq: Equalizer? = null
    private var gamePkg: String? = null
    private var colorOn = false
    private var prevFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nm = getSystemService(NotificationManager::class.java)
        startFg()
        prevFilter = nm.currentInterruptionFilter
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        gamePkg = intent?.getStringExtra("pkg") ?: gamePkg
        GameUtil.cleanRam(this, gamePkg)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        try { eq?.release() } catch (_: Exception) {}
        if (colorOn) setColor(-1)
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

    // ---------- 120Hz ----------
    private fun display(): Display =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

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

    // ---------- Color (enemy visibility) ----------
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
    private fun addBubble() {
        val b = TextView(this).apply {
            text = "🎮"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1E88E5.toInt())
            }
        }
        bubbleLp = WindowManager.LayoutParams(
            dp(48), dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(200)
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
        handler.removeCallbacks(tick)
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        stats = null
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun seek(maxV: Int, cur: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = maxV
        progress = cur
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun btnRow(items: List<Pair<String, Int>>, onClick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (name, v) ->
            row.addView(Button(this).apply {
                text = name
                textSize = 11f
                setOnClickListener { onClick(v) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun openPanel() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE121212.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        stats = label("...").also { root.addView(it) }

        // Focus (DND)
        root.addView(Switch(this).apply {
            text = "Focus (শুধু alarm আসবে)"
            setTextColor(Color.WHITE)
            isChecked = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            setOnCheckedChangeListener { _, on ->
                if (!nm.isNotificationPolicyAccessGranted) {
                    toast("অ্যাপে গিয়ে DND permission দাও"); isChecked = !on; return@setOnCheckedChangeListener
                }
                nm.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_ALARMS else NotificationManager.INTERRUPTION_FILTER_ALL
                )
            }
        })

        // Brightness
        root.addView(label("☀ Brightness"))
        val curB = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
        root.addView(seek(255, curB) { p ->
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, max(p, 1))
            } else toast("অ্যাপে গিয়ে Brightness permission দাও")
        })

        // Volume
        root.addView(label("🔊 Volume"))
        root.addView(seek(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            am.getStreamVolume(AudioManager.STREAM_MUSIC)) { p ->
            am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
        })

        // Sound preset
        root.addView(label("🎧 Sound"))
        root.addView(btnRow(listOf("Normal" to 0, "Bass" to 1, "Footstep" to 2)) { applyEq(it) })

        // Color (enemy visibility)
        root.addView(label("🎨 Enemy রং (একটা একটা করে try করো)"))
        root.addView(btnRow(listOf("Off" to -1, "A" to 12, "B" to 11, "C" to 13)) { setColor(it) })

        // Actions
        root.addView(Button(this).apply {
            text = "🧹 RAM পরিষ্কার"
            setOnClickListener {
                val n = GameUtil.cleanRam(this@OverlayService, gamePkg)
                toast("$n টা app বন্ধ করা হলো")
                updateStats()
            }
        })
        root.addView(Button(this).apply {
            text = "✖ Game Mode বন্ধ"
            setOnClickListener { stopSelf() }
        })

        val lp = WindowManager.LayoutParams(
            dp(290), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        applyHz(lp)
        wm.addView(root, lp)
        panel = root
        handler.post(tick)
    }

    private fun updateStats() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val hz = try { display().refreshRate.toInt() } catch (_: Exception) { 0 }
        stats?.text = "🔋 ${lvl * 100 / scale}%   🌡 $temp°C\n" +
            "🧠 খালি RAM: ${mi.availMem / 1048576} MB\n" +
            "🖥 Display: $hz Hz"
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
