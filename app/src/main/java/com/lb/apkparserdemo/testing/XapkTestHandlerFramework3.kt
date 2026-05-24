package com.lb.apkparserdemo.testing

import android.content.Context
import android.os.Build
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipFileFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.ZipInputStreamFilter
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import com.lb.common_utils.closeSilently
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler that reliably iterates XAPK entries using Apache [ZipFile]
 * and implements optimized nested access for inner APKs.
 */
class XapkTestHandlerFramework3(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 3: Started")
        var result: ApkParsingResult? = null

        var xapkFile: ZipFile? = null
        try {
            val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
            if (useApacheApi) {
                xapkFile = ZipFile.builder().setFile(xapkFileOnDisk).get()

                var packageName: String? = null
                var versionCode: Long? = null
                val splitApkEntriesList = ArrayList<Pair<ZipArchiveEntry, ApkManifestParser.SimpleApkInfo>>()
                var baseApkEntry: ZipArchiveEntry? = null

                xapkFile.use { xf ->
                    val entries = xf.entries
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                            continue
                        }
                        val apkInfo = xf.getInputStream(entry).use {
                            ApkManifestParser.findAndParseManifest(it, preferApacheApiWhenPossible = true)
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
                        Log.e("AppLog", "XAPK Test Framework 3: Failed to find base APK")
                        return null
                    }

                    val matchingSplitEntries = splitApkEntriesList
                            .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                            .map { it.first }

                    val baseApkInfo = getApkInfo(xapkFileOnDisk, xf, baseApkEntry!!, deviceConfig, preferApacheApiWhenPossible = true)
                    val splitApkInfoList = matchingSplitEntries.mapNotNull { getApkInfo(xapkFileOnDisk, xf, it, deviceConfig, preferApacheApiWhenPossible = true) }

                    val matchingApkEntries = matchingSplitEntries.toMutableList()
                    matchingApkEntries.add(baseApkEntry!!)

                    val filters = matchingApkEntries.map { createZipFilter(xapkFileOnDisk, xf, it, preferApacheApiWhenPossible = true) }
                    try {
                        val baseFilter = filters.last()
                        val consolidatedInfo = ApkInfo.getConsolidatedApkInfo(deviceConfig, baseApkInfo!!,
                                NonClosingZipFilter(baseFilter), splitApkInfoList)
                        if (consolidatedInfo != null) {
                            val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                                MultiZipFilter(matchingApkEntries.indices.map { i ->
                                    val filter = filters[i]
                                    if (filter.isSeekable) NonClosingZipFilter(filter)
                                    else createZipFilter(xapkFileOnDisk, xf, matchingApkEntries[i], preferApacheApiWhenPossible = true)
                                })
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
            } else {
                Log.w("AppLog", "XAPK Test Framework 3: Fast path failed, using slow path")
                java.io.FileInputStream(xapkFileOnDisk).use { fis ->
                    ZipInputStream(fis).use { zis ->
                        var baseApkName: String? = null
                        var packageNameSlow: String? = null
                        var versionCodeSlow: Long? = null
                        val splitApkNamesList = ArrayList<Pair<String, ApkManifestParser.SimpleApkInfo>>()
                        while (true) {
                            val entry = zis.nextEntry ?: break
                            if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                                continue
                            }
                            val apkInfo = ApkManifestParser.findAndParseManifest(zis, preferApacheApiWhenPossible = false) ?: continue
                            if (!apkInfo.isSplit) {
                                baseApkName = entry.name
                                packageNameSlow = apkInfo.packageName
                                versionCodeSlow = apkInfo.versionCode
                                splitApkNamesList.removeAll {
                                    it.second.packageName != packageNameSlow || it.second.versionCode != versionCodeSlow
                                }
                            } else {
                                if ((packageNameSlow != null && apkInfo.packageName != packageNameSlow) || (versionCodeSlow != null && apkInfo.versionCode != versionCodeSlow))
                                    continue
                                splitApkNamesList.add(entry.name to apkInfo)
                            }
                        }
                        if (baseApkName == null || packageNameSlow == null) return null

                        val matchingApkNames = splitApkNamesList.map { it.first }.toMutableList()
                        matchingApkNames.add(baseApkName!!)

                        val createSlowFilter = { name: String ->
                            val stream = java.io.FileInputStream(xapkFileOnDisk).let { ins ->
                                val outerZis = ZipInputStream(ins)
                                var e = outerZis.nextEntry
                                while (e != null && e.name != name) {
                                    e = outerZis.nextEntry
                                }
                                ZipInputStream(outerZis)
                            }
                            ZipInputStreamFilter(stream)
                        }

                        val filters = matchingApkNames.map { createSlowFilter(it) }
                        try {
                            val baseFilter = filters.last()
                            val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                            val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)
                            if (consolidatedInfo != null) {
                                val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                                    MultiZipFilter(matchingApkNames.map { createSlowFilter(it) })
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
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test Framework 3: Error", e)
        } finally {
            xapkFile.closeSilently()
        }
        return result
    }

    private fun getApkInfo(xapkFileOnDisk: File, xapk: ZipFile, entry: ZipArchiveEntry, deviceConfig: DeviceConfig, preferApacheApiWhenPossible: Boolean): ApkInfo? {
        return try {
            val filter = createZipFilter(xapkFileOnDisk, xapk, entry, preferApacheApiWhenPossible)
            filter.use {
                ApkInfo.internalGetApkInfo(deviceConfig, filter, requestParseResources = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createZipFilter(xapkFileOnDisk: File, xapk: ZipFile, entry: ZipArchiveEntry, preferApacheApiWhenPossible: Boolean): AbstractZipFilter {
        val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
        if (useApacheApi && entry.method == ZipArchiveEntry.STORED) {
            try {
                val channel = FileSeekableByteChannel(xapkFileOnDisk, entry.dataOffset, entry.size)
                val innerApkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                return ApacheZipFileFilter(context, innerApkFile, underlyingChannel = channel)
            } catch (_: Exception) {
            }
        }
        return ZipInputStreamFilter(ZipInputStream(xapk.getInputStream(entry)))
    }
}
