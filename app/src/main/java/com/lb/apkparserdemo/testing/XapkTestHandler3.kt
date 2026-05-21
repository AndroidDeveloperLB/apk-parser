package com.lb.apkparserdemo.testing

import android.content.Context
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipArchiveInputStreamFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

/**
 * XAPK Test Handler that reuses the XAPK handle and uses sequential scans with inclusion logic.
 * Uses [ApacheZipArchiveInputStreamFilter] for processing nested APKs as streams.
 */
class XapkTestHandler3(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test 3: Started")
        var result: ApkParsingResult? = null
        try {
            ZipFile.builder().setFile(xapkFileOnDisk).get().use { xapk ->
                var baseApkEntry: ZipArchiveEntry? = null
                var packageName: String? = null
                var versionCode: Long? = null
                val entries = xapk.entries
                val splitApkEntriesList = ArrayList<Pair<ZipArchiveEntry, ApkManifestParser.SimpleApkInfo>>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                        continue
                    }
                    val apkInfo = xapk.getInputStream(entry).use {
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
                        splitApkEntriesList.add(Pair(entry, apkInfo))
                    }
                }
                if (baseApkEntry == null || packageName == null) {
                    Log.e("AppLog", "XAPK Test 3: Failed to find base APK")
                    return null
                }

                val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                matchingApkEntries.add(baseApkEntry)

                val filters = matchingApkEntries.map { createZipFilter(xapk, it) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.map { createZipFilter(xapk, it) })
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
                    filters.forEach { it.close() }
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test 3: Error", e)
        }
        Log.d("AppLog", "XAPK Test 3: Finished with result: $result")
        return result
    }

    private fun createZipFilter(xapkFile: ZipFile, entry: ZipArchiveEntry): AbstractZipFilter {
        val inputStream = xapkFile.getInputStream(entry)
        val zipArchiveInputStream = ZipArchiveInputStream(inputStream)
        return ApacheZipArchiveInputStreamFilter(zipArchiveInputStream)
    }

}
