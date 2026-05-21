package com.lb.apkparserdemo.testing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MemoryUtils
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.ZipInputStreamFilter
import com.lb.common_utils.closeSilently
import net.dongliu.apk.parser.bean.DeviceConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler using [java.util.zip.ZipFile] for the XAPK and [ZipInputStreamFilter]
 * for the nested APKs, including an optional memory cache for smaller entries.
 */
class XapkTestHandlerFramework4(private val context: Context) {
    private val apkMemoryCache = HashMap<String, ByteArray>()

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 4: Started. useMemoryCache:$useMemoryCache")
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
            if (baseApkEntry == null || packageName == null || versionCode == null) {
                Log.e("AppLog", "XAPK Test Framework 4: Failed to find base APK")
                return null
            }

            val matchingSplitEntries = splitApkEntriesList
                    .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                    .map { it.first }

            val baseApkInfo = getApkInfo(context, xapkFile!!, baseApkEntry, deviceConfig, useMemoryCache)
            val splitApkInfoList = matchingSplitEntries.mapNotNull { getApkInfo(context, xapkFile!!, it, deviceConfig, useMemoryCache) }

            val matchingApkEntries = matchingSplitEntries.toMutableList()
            matchingApkEntries.add(baseApkEntry)

            var baseFilter: AbstractZipFilter? = null
            try {
                baseFilter = createZipFilter(context, xapkFile!!, baseApkEntry, useMemoryCache)
                val consolidatedInfo = ApkInfo.getConsolidatedApkInfo(deviceConfig, baseApkInfo!!,
                        baseFilter, splitApkInfoList)
                if (consolidatedInfo != null) {
                    val apkIcon: Bitmap? = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                        MultiZipFilter(matchingApkEntries.map { createZipFilter(context, xapkFile!!, it, useMemoryCache) })
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
            Log.e("AppLog", "XAPK Test Framework 4: Error", e)
        } finally {
            xapkFile.closeSilently()
        }
        Log.d("AppLog", "XAPK Test Framework 4: Finished with result: $result")
        return result
    }

    private fun getApkInfo(context: Context, xapkFile: ZipFile, entry: ZipEntry, deviceConfig: DeviceConfig, useMemoryCache: Boolean): ApkInfo? {
        return try {
            val filter = createZipFilter(context, xapkFile, entry, useMemoryCache)
            filter.use {
                ApkInfo.internalGetApkInfo(deviceConfig, filter, requestParseResources = true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createZipFilter(context: Context, xapkFile: ZipFile, entry: ZipEntry, useMemoryCache: Boolean): AbstractZipFilter {
        if (useMemoryCache) {
            apkMemoryCache[entry.name]?.let { cachedBytes ->
                return ZipInputStreamFilter(ZipInputStream(ByteArrayInputStream(cachedBytes)))
            }
            if (entry.size > 0 && entry.size < 10 * 1024 * 1024 && MemoryUtils.isEnoughMemoryForApkParsing(entry.size)) {
                try {
                    val bytes = xapkFile.getInputStream(entry).use { it.readBytes() }
                    apkMemoryCache[entry.name] = bytes
                    return ZipInputStreamFilter(ZipInputStream(ByteArrayInputStream(bytes)))
                } catch (e: Throwable) {
                }
            }
        }
        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }
}
