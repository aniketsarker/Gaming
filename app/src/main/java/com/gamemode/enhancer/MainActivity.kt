package com.gamemode.enhancer

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gm", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun games(): MutableSet<String> =
        HashSet(prefs.getStringSet("games", emptySet()) ?: emptySet())

    private fun tv(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun permBtn(label: String, ok: Boolean, action: String, withPkg: Boolean) =
        Button(this).apply {
            text = (if (ok) "✅ " else "❌ ") + label
            setOnClickListener {
                val i = Intent(action)
                if (withPkg) i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
        }

    private fun row(pkg: String, name: String, action: () -> View): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        r.addView(
            ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(pkg)) },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        r.addView(
            tv(name).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        r.addView(action())
        return r
    }

    private fun render() {
        val pm = packageManager
        val nm = getSystemService(NotificationManager::class.java)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        col.addView(tv("🎮 Game Enhancer", 24f, true))
        col.addView(tv("আগে ৩টা permission দাও", 13f))
        col.addView(permBtn("Overlay (ভাসমান আইকন)", Settings.canDrawOverlays(this),
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true))
        col.addView(permBtn("Brightness কন্ট্রোল", Settings.System.canWrite(this),
            Settings.ACTION_MANAGE_WRITE_SETTINGS, true))
        col.addView(permBtn("Do Not Disturb (Focus)", nm.isNotificationPolicyAccessGranted,
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, false))

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val selected = games()

        col.addView(tv("আমার Games", 18f, true))
        val mine = apps.filter { it.first in selected }
        if (mine.isEmpty()) col.addView(tv("নিচ থেকে game টিক দাও, তারপর অ্যাপ আবার খোলো", 13f))
        for ((pkg, name) in mine) {
            col.addView(row(pkg, name) {
                Button(this).apply {
                    text = "▶ Launch"
                    setOnClickListener { launch(pkg) }
                }
            })
        }

        col.addView(tv("Game যোগ করো", 18f, true))
        for ((pkg, name) in apps) {
            col.addView(row(pkg, name) {
                CheckBox(this).apply {
                    isChecked = pkg in selected
                    setOnCheckedChangeListener { _, c ->
                        val s = games()
                        if (c) s.add(pkg) else s.remove(pkg)
                        prefs.edit().putStringSet("games", s).apply()
                    }
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun launch(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "আগে Overlay permission দাও", Toast.LENGTH_LONG).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startForegroundService(Intent(this, OverlayService::class.java).putExtra("pkg", pkg))
        startActivity(intent)
    }
}
