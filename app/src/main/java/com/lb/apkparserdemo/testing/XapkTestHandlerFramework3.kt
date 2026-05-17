package com.lb.apkparserdemo.testing

import android.content.Context
import android.util.Log
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.ZipInputStreamFilter
import com.lb.common_utils.closeSilently
import net.dongliu.apk.parser.bean.DeviceConfig
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler using [java.util.zip.ZipFile] to reliably iterate the XAPK entries
 * and [ZipInputStreamFilter] to sequentially parse the inner APKs.
 */
class XapkTestHandlerFramework3(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 3: Started")
//        val startTime = System.currentTimeMillis()
        var result: ApkParsingResult? = null

        try {
            var baseApkName: String? = null
            var baseApkInfo: ApkInfo? = null
            var packageName: String? = null
            var versionCode: Long? = null
            val splitApkNamesList = ArrayList<Pair<String, ApkManifestParser.SimpleApkInfo>>()

            // First pass: find all APKs and identify base using ZipFile for reliability
            ZipFile(xapkFileOnDisk).use { xapk ->
                val entries = xapk.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                        continue
                    }
                    val apkInfo = xapk.getInputStream(entry).use {
                        ApkManifestParser.findAndParseManifest(it)
                    } ?: continue
                    if (!apkInfo.isSplit) {
                        baseApkName = entry.name
                        packageName = apkInfo.packageName
                        versionCode = apkInfo.versionCode
                        splitApkNamesList.removeAll {
                            it.second.packageName != packageName || it.second.versionCode != versionCode
                        }
                    } else {
                        if ((packageName != null && apkInfo.packageName != packageName) || (versionCode != null && apkInfo.versionCode != versionCode))
                            continue
                        splitApkNamesList.add(entry.name to apkInfo)
                    }
                }
            }

            if (baseApkName == null || packageName == null || versionCode == null) {
                Log.e("AppLog", "XAPK Test Framework 3: Failed to find base APK")
                return null
            }

            val matchingSplitNames = splitApkNamesList
                    .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                    .map { it.first }

            baseApkInfo = getApkInfo(xapkFileOnDisk, baseApkName!!, deviceConfig)
            val splitApkInfoList = matchingSplitNames.mapNotNull { getApkInfo(xapkFileOnDisk, it, deviceConfig) }

            val matchingApkNames = matchingSplitNames.toMutableList()
            matchingApkNames.add(baseApkName!!)

            val createFilter = { entryName: String ->
                val xapk = ZipFile(xapkFileOnDisk)
                val entry = xapk.getEntry(entryName)
                ZipInputStreamFilter(ZipInputStream(xapk.getInputStream(entry)))
            }

            val baseFilter = createFilter(baseApkName!!)
            try {
                val consolidatedInfo = ApkInfo.getConsolidatedApkInfo(deviceConfig, baseApkInfo!!,
                        baseFilter, splitApkInfoList)
                if (consolidatedInfo != null) {
                    val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                        MultiZipFilter(matchingApkNames.map { createFilter(it) })
                    }, consolidatedInfo, appIconSize)
                    val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
                    val appLabel = apkMeta.label
                    if (apkIcon != null)
                        Log.d("AppLog", "XAPK Test Framework 3: Success apkIcon:${apkIcon.width}x${apkIcon.height} label:$appLabel packageName:${apkMeta.packageName} versionCode:${apkMeta.versionCode} versionName:${apkMeta.versionName}")
                    else
                        Log.e("AppLog", "XAPK Test Framework 3: Failed to get icon. label:$appLabel packageName:${apkMeta.packageName} versionCode:${apkMeta.versionCode} versionName:${apkMeta.versionName}")
                    result = ApkParsingResult(
                            packageName = apkMeta.packageName,
                            versionCode = apkMeta.versionCode,
                            versionName = apkMeta.versionName,
                            label = apkMeta.label,
                            icon = apkIcon
                    )
                }
            } finally {
                baseFilter.closeSilently()
            }

//            Log.d("AppLog", "XAPK Test Framework 3: Success in ${System.currentTimeMillis() - startTime} ms")
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test Framework 3: Error", e)
        }
        return result
    }

    private fun getApkInfo(xapkFileOnDisk: File, entryName: String, deviceConfig: DeviceConfig): ApkInfo? {
        return try {
            ZipFile(xapkFileOnDisk).use { xapk ->
                val entry = xapk.getEntry(entryName)
                val filter = ZipInputStreamFilter(ZipInputStream(xapk.getInputStream(entry)))
                filter.use {
                    ApkInfo.internalGetApkInfo(deviceConfig, filter, requestParseResources = true)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
