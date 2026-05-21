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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Basic XAPK Test Handler using the standard Android Framework [java.util.zip.ZipFile] and [java.util.zip.ZipInputStream].
 */
class XapkTestHandlerFramework1(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 1: Started")
        var result: ApkParsingResult? = null

        var xapkFile: ZipFile? = null
        try {
            xapkFile = ZipFile(xapkFileOnDisk)
            var baseApkEntry: ZipEntry? = null
            var packageName: String? = null
            var versionCode: Long? = null
            val entries = xapkFile.entries()
            val splitApkEntriesList = ArrayList<Pair<ZipEntry, ApkManifestParser.SimpleApkInfo>>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                    continue
                }
                val apkInfo = xapkFile.getInputStream(entry).use {
                    ApkManifestParser.findAndParseManifest(it)
                } ?: continue
                if (!apkInfo.isSplit) {
                    baseApkEntry = entry
                    packageName = apkInfo.packageName
                    versionCode = apkInfo.versionCode
                    splitApkEntriesList.removeAll {
                        it.second.packageName != packageName || it.second.versionCode != versionCode
                    }
                } else {
                    if ((packageName != null && apkInfo.packageName != packageName) || (versionCode != null && apkInfo.versionCode != versionCode))
                        continue
                    splitApkEntriesList.add(entry to apkInfo)
                }
            }
            if (baseApkEntry == null || packageName == null || versionCode == null) return null

            val matchingSplitEntries = splitApkEntriesList
                    .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                    .map { it.first }

            val baseApkInfo = getApkInfo(xapkFile!!, baseApkEntry, deviceConfig)
            val splitApkInfoList = matchingSplitEntries.mapNotNull { getApkInfo(xapkFile!!, it, deviceConfig) }

            val matchingApkEntries = matchingSplitEntries.toMutableList()
            matchingApkEntries.add(baseApkEntry)

            val baseFilter = ZipInputStreamFilter(ZipInputStream(xapkFile!!.getInputStream(baseApkEntry)))
            try {
                val consolidatedInfo = ApkInfo.getConsolidatedApkInfo(deviceConfig, baseApkInfo!!,
                        baseFilter, splitApkInfoList)
                if (consolidatedInfo != null) {
                    val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                        MultiZipFilter(matchingApkEntries.map { ZipInputStreamFilter(ZipInputStream(xapkFile!!.getInputStream(it))) })
                    }, consolidatedInfo, appIconSize)
                    val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
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
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test Framework 1: Error", e)
        } finally {
            xapkFile.closeSilently()
        }
        Log.d("AppLog", "XAPK Test Framework 1: Finished with result: $result")
        return result
    }

    private fun getApkInfo(xapkFile: ZipFile, entry: ZipEntry, deviceConfig: DeviceConfig): ApkInfo? {
        return try {
            val filter = ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
            filter.use {
                ApkInfo.internalGetApkInfo(deviceConfig, filter, requestParseResources = true)
            }
        } catch (_: Exception) {
            null
        }
    }

}
