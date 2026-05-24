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
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler using Apache Commons Compress.
 * Efficiently parses XAPK files (ZIPs containing multiple APKs) by using
 * [FileSeekableByteChannel] to access nested APK entries via random access.
 */
class XapkTestHandler(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test: Started")
        var result: ApkParsingResult? = null
        try {
            val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
            if (useApacheApi) {
                val xapk = ZipFile.builder().setFile(xapkFileOnDisk).get()
                xapk.use { xapkFile ->
                    var baseApkEntry: ZipArchiveEntry? = null
                    var packageName: String? = null
                    var versionCode: Long? = null
                    val entries = xapkFile.entries
                    val splitApkEntriesList = ArrayList<Pair<ZipArchiveEntry, ApkManifestParser.SimpleApkInfo>>()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                            continue
                        }
                        val apkInfo = xapkFile.getInputStream(entry).use {
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
                            splitApkEntriesList.add(Pair(entry, apkInfo))
                        }
                    }
                    if (baseApkEntry == null || packageName == null) {
                        Log.e("AppLog", "XAPK Test: Failed to find base APK")
                        return null
                    }

                    val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                    splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                    matchingApkEntries.add(baseApkEntry)

                    val filters = matchingApkEntries.map { createApacheZipFilter(context, xapkFileOnDisk, xapkFile, it, preferApacheApiWhenPossible = true) }
                    try {
                        val baseFilter = filters.last()
                        val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                        val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                        if (consolidatedInfo != null) {
                            val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                                MultiZipFilter(matchingApkEntries.indices.map { i ->
                                    val filter = filters[i]
                                    if (filter.isSeekable) NonClosingZipFilter(filter)
                                    else createApacheZipFilter(context, xapkFileOnDisk, xapkFile, matchingApkEntries[i], preferApacheApiWhenPossible = true)
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
                // Fallback for API 24 or broken environments: use standard ZipInputStream
                Log.w("AppLog", "XAPK Test: Fast path failed, using slow path")
                java.io.FileInputStream(xapkFileOnDisk).use { fis ->
                    ZipInputStream(fis).use { zis ->
                        var baseApkName: String? = null
                        var packageName: String? = null
                        var versionCode: Long? = null
                        val splitApkNamesList = ArrayList<Pair<String, ApkManifestParser.SimpleApkInfo>>()
                        while (true) {
                            val entry = zis.nextEntry ?: break
                            if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                                continue
                            }
                            val apkInfo = ApkManifestParser.findAndParseManifest(zis, preferApacheApiWhenPossible = false) ?: continue
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
                                splitApkNamesList.add(Pair(entry.name, apkInfo))
                            }
                        }
                        if (baseApkName == null || packageName == null) {
                            Log.e("AppLog", "XAPK Test (Slow): Failed to find base APK")
                            return null
                        }

                        val matchingApkNames = ArrayList<String>(splitApkNamesList.size + 1)
                        splitApkNamesList.forEach { matchingApkNames.add(it.first) }
                        matchingApkNames.add(baseApkName)

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
            Log.e("AppLog", "XAPK Test: Error", e)
        }
        return result
    }

    private fun createApacheZipFilter(context: Context, xapkFileOnDisk: File, xapk: ZipFile, entry: ZipArchiveEntry, preferApacheApiWhenPossible: Boolean): AbstractZipFilter {
        val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
        if (useApacheApi && entry.method == ZipArchiveEntry.STORED) {
            try {
                val channel = FileSeekableByteChannel(xapkFileOnDisk, entry.dataOffset, entry.size)
                val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
            } catch (e: Throwable) {
            }
        }
        return ZipInputStreamFilter(ZipInputStream(xapk.getInputStream(entry)))
    }

}
