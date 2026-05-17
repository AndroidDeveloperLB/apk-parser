package com.lb.apkparserdemo.apk_info

import android.graphics.Bitmap

/**
 * Data class containing the final, user-facing results of parsing an APK.
 *
 * @property packageName The package name of the app.
 * @property versionCode The version code of the app.
 * @property versionName The version name of the app.
 * @property label The localized app label (name).
 * @property icon The app icon as a [Bitmap].
 */
data class ApkParsingResult(
        val packageName: String?,
        val versionCode: Long?,
        val versionName: String?,
        val label: String?,
        val icon: Bitmap?
)
