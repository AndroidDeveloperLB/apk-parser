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
import com.lb.apkparserdemo.apk_info.ZipInputStreamFilter
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import com.lb.common_utils.readBytesIntoByteArray
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler using Apache Commons Compress [ZipFile] to iterate the XAPK
 * and implementing nested random access for inner APKs. Supports optional memory caching.
 */
class XapkTestHandlerFramework5(private val context: Context) {
    private val apkMemoryCache = HashMap<String, ByteArray>()

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 5: Started. useMemoryCache:$useMemoryCache")
        var result: ApkParsingResult? = null

        try {
            ZipFile.builder().setFile(xapkFileOnDisk).get().use { xapk ->
                var baseApkName: String? = null
                var packageName: String? = null
                var versionCode: Long? = null
                val splitApkNamesList = ArrayList<Pair<String, ApkManifestParser.SimpleApkInfo>>()

                val entries = xapk.entries
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

                if (baseApkName == null || packageName == null || versionCode == null) {
                    Log.e("AppLog", "XAPK Test Framework 5: Failed to find base APK")
                    return null
                }

                val matchingApkNames = splitApkNamesList
                        .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                        .map { it.first }
                        .toMutableList()
                matchingApkNames.add(baseApkName!!)

                val createFilterForName = { entryName: String ->
                    val entry = xapk.getEntry(entryName)
                    createFilter(xapkFileOnDisk, xapk, entry, useMemoryCache)
                }

                val filters = matchingApkNames.map { createFilterForName(it) }
                try {
                    val baseFilter = filters.last() // baseApkName was added last
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkNames.map { createFilterForName(it) })
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
            Log.e("AppLog", "XAPK Test Framework 5: Error", e)
        }
        Log.d("AppLog", "XAPK Test Framework 5: Finished with result: $result")
        return result
    }

    private fun createFilter(xapkFileOnDisk: File, xapk: ZipFile, entry: ZipArchiveEntry, useMemoryCache: Boolean): AbstractZipFilter {
        if (useMemoryCache) {
            apkMemoryCache[entry.name]?.let { cachedBytes ->
                val channelInMemory = SeekableInMemoryByteChannel(cachedBytes)
                val apkFile = ZipFile.builder().setSeekableByteChannel(channelInMemory).get()
                return ApacheZipFileFilter(context, apkFile, underlyingChannel = channelInMemory)
            }
            val size = entry.size
            if (size > 0 && size < 10 * 1024 * 1024 && MemoryUtils.isEnoughMemoryForApkParsing(size)) {
                try {
                    val bytes = ByteArray(size.toInt())
                    xapk.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                    apkMemoryCache[entry.name] = bytes
                    val channelInMemory = SeekableInMemoryByteChannel(bytes)
                    val apkFile = ZipFile.builder().setSeekableByteChannel(channelInMemory).get()
                    return ApacheZipFileFilter(context, apkFile, underlyingChannel = channelInMemory)
                } catch (_: Throwable) {
                }
            }
        }

        if (entry.method == ZipArchiveEntry.STORED) {
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
