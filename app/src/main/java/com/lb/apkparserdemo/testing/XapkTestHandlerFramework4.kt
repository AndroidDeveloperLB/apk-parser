package com.lb.apkparserdemo.testing

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipFileFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MemoryUtils
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.ZipInputStreamFilter
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import com.lb.common_utils.closeSilently
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler using [java.util.zip.ZipFile] for the XAPK and [ZipInputStreamFilter]
 * for the nested APKs, including an optional memory cache for smaller entries.
 */
class XapkTestHandlerFramework4(private val context: Context) {
    private val apkMemoryCache = HashMap<String, ByteArray>()

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 4: Started. useMemoryCache:$useMemoryCache")
        var result: ApkParsingResult? = null

        var xapkFile: ZipFile? = null
        try {
            val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
            if (useApacheApi) {
                xapkFile = ZipFile.builder().setFile(xapkFileOnDisk).get()

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

                val baseApkInfo = getApkInfo(context, xapkFileOnDisk, xapkFile, baseApkEntry, deviceConfig, useMemoryCache, preferApacheApiWhenPossible = true)
                val splitApkInfoList = matchingSplitEntries.mapNotNull { getApkInfo(context, xapkFileOnDisk, xapkFile, it, deviceConfig, useMemoryCache, preferApacheApiWhenPossible = true) }

                val matchingApkEntries = matchingSplitEntries.toMutableList()
                matchingApkEntries.add(baseApkEntry)

                val filters = matchingApkEntries.map { createZipFilter(context, xapkFileOnDisk, xapkFile, it, useMemoryCache, preferApacheApiWhenPossible = true) }
                var baseFilter: AbstractZipFilter? = null
                try {
                    baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.getConsolidatedApkInfo(deviceConfig, baseApkInfo!!,
                            NonClosingZipFilter(baseFilter), splitApkInfoList)
                    if (consolidatedInfo != null) {
                        val apkIcon: Bitmap? = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.indices.map { i ->
                                val filter = filters[i]
                                if (filter.isSeekable) NonClosingZipFilter(filter)
                                else createZipFilter(context, xapkFileOnDisk, xapkFile, matchingApkEntries[i], useMemoryCache, preferApacheApiWhenPossible = true)
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
            } else {
                Log.w("AppLog", "XAPK Test Framework 4: Fast path failed, using slow path")
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
                                splitApkNamesList.add(entry.name to apkInfo)
                            }
                        }
                        if (baseApkName == null || packageName == null) return null

                        val matchingApkNames = splitApkNamesList.map { it.first }.toMutableList()
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
            Log.e("AppLog", "XAPK Test Framework 4: Error", e)
        } finally {
            xapkFile.closeSilently()
        }
        return result
    }

    private fun getApkInfo(context: Context, xapkFileOnDisk: File, xapkFile: ZipFile, entry: ZipArchiveEntry, deviceConfig: DeviceConfig, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean): ApkInfo? {
        return try {
            val filter = createZipFilter(context, xapkFileOnDisk, xapkFile, entry, useMemoryCache, preferApacheApiWhenPossible)
            filter.use {
                ApkInfo.internalGetApkInfo(deviceConfig, filter, requestParseResources = true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createZipFilter(context: Context, xapkFileOnDisk: File, xapkFile: ZipFile, entry: ZipArchiveEntry, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean): AbstractZipFilter {
        val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
        if (useApacheApi && useMemoryCache) {
            apkMemoryCache[entry.name]?.let { cachedBytes ->
                val channel = SeekableInMemoryByteChannel(cachedBytes)
                val apkFile = try {
                    ZipFile.builder().setSeekableByteChannel(channel).get()
                } catch (e: Throwable) {
                    null
                }
                if (apkFile != null) {
                    return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
                }
            }
            if (entry.size > 0 && entry.size < 10 * 1024 * 1024 && MemoryUtils.isEnoughMemoryForApkParsing(entry.size)) {
                try {
                    val bytes = xapkFile.getInputStream(entry).use { it.readBytes() }
                    apkMemoryCache[entry.name] = bytes
                    val channel = SeekableInMemoryByteChannel(bytes)
                    val apkFile = try {
                        ZipFile.builder().setSeekableByteChannel(channel).get()
                    } catch (e: Throwable) {
                        null
                    }
                    if (apkFile != null) {
                        return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
                    }
                } catch (e: Throwable) {
                }
            }
        }

        if (useApacheApi && entry.method == ZipArchiveEntry.STORED) {
            try {
                val channel = FileSeekableByteChannel(xapkFileOnDisk, entry.dataOffset, entry.size)
                val innerApkFile = try {
                    ZipFile.builder().setSeekableByteChannel(channel).get()
                } catch (e: Throwable) {
                    null
                }
                if (innerApkFile != null) {
                    return ApacheZipFileFilter(context, innerApkFile, underlyingChannel = channel)
                }
            } catch (_: Exception) {
            }
        }
        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }
}
