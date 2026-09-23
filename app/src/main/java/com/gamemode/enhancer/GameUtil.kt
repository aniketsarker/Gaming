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
}
