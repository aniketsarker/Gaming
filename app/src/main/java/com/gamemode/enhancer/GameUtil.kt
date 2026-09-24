package com.gamemode.enhancer

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

object GameUtil {
    /** Background-এর user app গুলো বন্ধ করে RAM খালি করে। */
    fun cleanRam(ctx: Context, keep: String?): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var n = 0
        for (app in ctx.packageManager.getInstalledApplications(0)) {
            if (app.packageName == ctx.packageName || app.packageName == keep) continue
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            am.killBackgroundProcesses(app.packageName)
            n++
        }
        return n
    }

    /** Game কিনা: নিজে বাদ/যোগ করলে সেটা, নইলে Play Store ক্যাটাগরি দেখে। */
    @Suppress("DEPRECATION")
    fun isGame(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return false
        val p = ctx.getSharedPreferences("gm", Context.MODE_PRIVATE)
        if (pkg in (p.getStringSet("rem", emptySet()) ?: emptySet())) return false
        if (pkg in (p.getStringSet("add", emptySet()) ?: emptySet())) return true
        return try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ai.category == ApplicationInfo.CATEGORY_GAME ||
                (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (_: Exception) {
            false
        }
    }

    fun setGame(ctx: Context, pkg: String, on: Boolean) {
        val p = ctx.getSharedPreferences("gm", Context.MODE_PRIVATE)
        val add = HashSet(p.getStringSet("add", emptySet()) ?: emptySet())
        val rem = HashSet(p.getStringSet("rem", emptySet()) ?: emptySet())
        if (on) { rem.remove(pkg); add.add(pkg) } else { add.remove(pkg); rem.add(pkg) }
        p.edit().putStringSet("add", add).putStringSet("rem", rem).apply()
    }
}
