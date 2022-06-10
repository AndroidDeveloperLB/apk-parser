package com.lb.apkparserdemo.apk_info.app_icon

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.lb.apkparserdemo.getSystemService

fun ApplicationInfo.isSystemApp() = this.flags and ApplicationInfo.FLAG_SYSTEM != 0

fun PackageInfo.isSystemApp() = this.applicationInfo.isSystemApp()

object AppInfoUtil {
    private var appIconSize = 0

    fun getAppIconSize(context: Context): Int {
        if (appIconSize > 0)
            return appIconSize
        val activityManager = context.getSystemService<ActivityManager>()
        appIconSize = try {
            activityManager.launcherLargeIconSize
        } catch (e: Exception) {
            ViewUtil.convertDpToPixels(context, 48f).toInt()
        }
        return appIconSize
    }
}
