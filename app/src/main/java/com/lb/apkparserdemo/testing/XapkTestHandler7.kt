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
 * Advanced XAPK Test Handler mimicking ContentProvider access (InputStream only).
 * Uses [SeekableInputStreamByteChannel] to provide random access over a non-seekable source.
 * Demonstrates memory caching for smaller APK entries to speed up icon fetching.
 */
class XapkTestHandler7(private val context: Context) {
    private val apkMemoryCache = HashMap<String, ByteArray>()

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        val inputStreamProvider = { FileInputStream(xapkFileOnDisk) }
        Log.d("AppLog", "XAPK Test 7: Started. useMemoryCache:$useMemoryCache")
        var result: ApkParsingResult? = null

        val xapkChannel = object : SeekableInputStreamByteChannel(xapkFileOnDisk.length()) {
            override fun getNewInputStream(): InputStream = inputStreamProvider()
        }

        try {
            val useApacheApi = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
            if (useApacheApi) {
                val xapk = ZipFile.builder().setSeekableByteChannel(xapkChannel).get()
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
                        Log.e("AppLog", "XAPK Test 7: Failed to find base APK")
                        return null
                    }

                    val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                    splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                    matchingApkEntries.add(baseApkEntry)

                    val filters = matchingApkEntries.map { createZipFilter(context, xapkFile, xapkChannel, it, useMemoryCache, preferApacheApiWhenPossible = true) }
                    try {
                        val baseFilter = filters.last()
                        val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                        val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                        if (consolidatedInfo != null) {
                            val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                                MultiZipFilter(matchingApkEntries.indices.map { i ->
                                    val filter = filters[i]
                                    if (filter.isSeekable) NonClosingZipFilter(filter)
                                    else createZipFilter(context, xapkFile, xapkChannel, matchingApkEntries[i], useMemoryCache, preferApacheApiWhenPossible = true)
                                })
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
            } else {
                Log.w("AppLog", "XAPK Test 7: Fast path failed, using slow ZipInputStream path")
                try {
                    xapkChannel.position(0L)
                } catch (_: Throwable) {
                }
                val inputStream = java.nio.channels.Channels.newInputStream(xapkChannel)
                ZipInputStream(inputStream).use { zis ->
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
                        Log.e("AppLog", "XAPK Test 7 (Slow): Failed to find base APK")
                        return null
                    }

                    val matchingApkNames = ArrayList<String>(splitApkNamesList.size + 1)
                    splitApkNamesList.forEach { matchingApkNames.add(it.first) }
                    matchingApkNames.add(baseApkName)

                    val createSlowFilter = { name: String ->
                        val ch = object : SeekableInputStreamByteChannel(xapkFileOnDisk.length()) {
                            override fun getNewInputStream(): InputStream = inputStreamProvider()
                        }
                        ch.position(0L)
                        val ins = java.nio.channels.Channels.newInputStream(ch)
                        val outerZis = ZipInputStream(ins)
                        var e = outerZis.nextEntry
                        while (e != null && e.name != name) {
                            e = outerZis.nextEntry
                        }
                        // Now outerZis is positioned at the start of the APK data.
                        // We wrap it in ANOTHER ZipInputStream to read the entries inside the APK.
                        ZipInputStreamFilter(ZipInputStream(outerZis))
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
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test 7: Error", e)
        }
        return result
    }

    private fun createZipFilter(context: Context, xapkFile: ZipFile, xapkChannel: SeekableByteChannel, entry: ZipArchiveEntry, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean): AbstractZipFilter {
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
            val size = entry.size
            if (size > 0 && size < 10 * 1024 * 1024 && MemoryUtils.isEnoughMemoryForApkParsing(size)) {
                try {
                    val bytes = ByteArray(size.toInt())
                    xapkFile.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                    apkMemoryCache[entry.name] = bytes
                    val channel = SeekableInMemoryByteChannel(bytes)
                    val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                    return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
                } catch (e: Throwable) {
                }
            }
        }

        if (useApacheApi && entry.method == ZipArchiveEntry.STORED) {
            try {
                val segmentChannel = BoundedSeekableByteChannel(xapkChannel, entry.dataOffset, entry.size)
                val innerApkFile = ZipFile.builder().setSeekableByteChannel(segmentChannel).get()
                return ApacheZipFileFilter(context, innerApkFile, underlyingChannel = segmentChannel)
            } catch (e: Throwable) {
            }
        }

        // fallback for deflated entries or if channel-based opening failed
        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }

}
