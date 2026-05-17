package com.lb.apkparserdemo.testing

import android.content.Context
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
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import com.lb.common_utils.readBytesIntoByteArray
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.File

/**
 * XAPK Test Handler that combines memory caching with ApacheZipFileFilter.
 * It reuses the XAPK handle and uses nested random access for efficiency.
 */
class XapkTestHandler5(private val context: Context) {
    private val apkMemoryCache = HashMap<String, ByteArray>()

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test 5: Started. useMemoryCache:$useMemoryCache")
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
                    Log.e("AppLog", "XAPK Test 5: Failed to find base APK")
                    return null
                }

                val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                matchingApkEntries.add(baseApkEntry)

                val filters = matchingApkEntries.map { createZipFilter(context, xapkFileOnDisk, xapk, it, useMemoryCache) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.map { createZipFilter(context, xapkFileOnDisk, xapk, it, useMemoryCache) })
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
            Log.e("AppLog", "XAPK Test 5: Error", e)
        }
        return result
    }

    private fun createZipFilter(context: Context, xapkFileOnDisk: File, xapk: ZipFile, entry: ZipArchiveEntry, useMemoryCache: Boolean): AbstractZipFilter {
        if (useMemoryCache) {
            apkMemoryCache[entry.name]?.let { cachedBytes ->
                val channel = SeekableInMemoryByteChannel(cachedBytes)
                val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
            }
            val size = entry.size
            if (size > 0 && size < 10 * 1024 * 1024 && MemoryUtils.isEnoughMemoryForApkParsing(size)) {
                try {
                    val bytes = ByteArray(size.toInt())
                    xapk.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                    apkMemoryCache[entry.name] = bytes
                    val channel = SeekableInMemoryByteChannel(bytes)
                    val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                    return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
                } catch (_: Throwable) {
                }
            }
        }
        val channel = FileSeekableByteChannel(xapkFileOnDisk, entry.dataOffset, entry.size)
        val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
        return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
    }

}
