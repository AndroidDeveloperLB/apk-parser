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
import com.lb.apkparserdemo.apk_info.zip.BoundedSeekableByteChannel
import com.lb.apkparserdemo.apk_info.zip.SeekableInputStreamByteChannel
import com.lb.common_utils.readBytesIntoByteArray
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipInputStream

/**
 * Advanced XAPK Test Handler that works with any [SeekableByteChannel].
 * It uses nested random access via [BoundedSeekableByteChannel] when possible,
 * supports memory caching, and uses Apache [ZipFile] for efficient XAPK iteration.
 */
class XapkTestHandlerFramework7(private val context: Context) {
    private val apkMemoryCache = HashMap<String, ByteArray>()

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean): ApkParsingResult? {
        val inputStreamProvider = { FileInputStream(xapkFileOnDisk) }
        Log.d("AppLog", "XAPK Test Framework 7: Started. useMemoryCache:$useMemoryCache")
        var result: ApkParsingResult? = null

        val xapkChannel = object : SeekableInputStreamByteChannel(xapkFileOnDisk.length()) {
            override fun getNewInputStream(): InputStream = inputStreamProvider()
        }

        try {
            ZipFile.builder().setSeekableByteChannel(xapkChannel).get().use { xapk ->
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
                        splitApkEntriesList.add(entry to apkInfo)
                    }
                }
                if (baseApkEntry == null || packageName == null || versionCode == null) {
                    Log.e("AppLog", "XAPK Test Framework 7: Failed to find base APK")
                    return null
                }

                val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                matchingApkEntries.add(baseApkEntry)

                val filters = matchingApkEntries.map { createZipFilter(context, xapk, xapkChannel, it, useMemoryCache) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.map { createZipFilter(context, xapk, xapkChannel, it, useMemoryCache) })
                        }, consolidatedInfo, appIconSize)
                        val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
                        result = ApkParsingResult(
                                packageName = apkMeta.packageName,
                                versionCode = apkMeta.versionCode,
                                versionName = apkMeta.versionName,
                                label = apkMeta.label,
                                icon = apkIcon,
                        )
                    }
                } finally {
                    filters.forEach { it.close() }
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test Framework 7: Error", e)
        } finally {
            xapkChannel.close()
        }
        Log.d("AppLog", "XAPK Test Framework 7: Finished with result: $result")
        return result
    }

    private fun createZipFilter(context: Context, xapkFile: ZipFile, xapkChannel: SeekableByteChannel, entry: ZipArchiveEntry, useMemoryCache: Boolean): AbstractZipFilter {
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
                    xapkFile.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                    apkMemoryCache[entry.name] = bytes
                    val channel = SeekableInMemoryByteChannel(bytes)
                    val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                    return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
                } catch (_: Throwable) {
                }
            }
        }

        return try {
            val segmentChannel = BoundedSeekableByteChannel(xapkChannel, entry.dataOffset, entry.size)
            val innerApkFile = ZipFile.builder().setSeekableByteChannel(segmentChannel).get()
            ApacheZipFileFilter(context, innerApkFile, underlyingChannel = segmentChannel)
        } catch (_: Exception) {
            val inputStream = xapkFile.getInputStream(entry)
            ZipInputStreamFilter(ZipInputStream(inputStream))
        }
    }
}
