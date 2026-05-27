package com.lb.apkparserdemo.testing

import android.content.Context
import android.os.Build
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipArchiveInputStreamFilter
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
import com.lb.common_utils.closeSilently
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
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

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        val inputStreamProvider = { FileInputStream(xapkFileOnDisk) }
        Log.d("AppLog", "XAPK Test Framework 7: Started. useMemoryCache:$useMemoryCache")
        var result: ApkParsingResult? = null

        val xapkChannel = object : SeekableInputStreamByteChannel(xapkFileOnDisk.length()) {
            override fun getNewInputStream(): InputStream = inputStreamProvider()
        }

        var xapkFile: ZipFile? = null
        try {
            val useApacheApi = Build.VERSION.SDK_INT >=  Build.VERSION_CODES.O && preferApacheApiWhenPossible
            if (useApacheApi) {
                xapkFile = ZipFile.builder().setSeekableByteChannel(xapkChannel).get()
                xapkFile!!.use { xf ->
                    var baseApkEntry: ZipArchiveEntry? = null
                    var packageName: String? = null
                    var versionCode: Long? = null
                    val entries = xf.entries
                    val splitApkEntriesList = ArrayList<Pair<ZipArchiveEntry, ApkManifestParser.SimpleApkInfo>>()
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
                        Log.e("AppLog", "XAPK Test Framework 7: Failed to find base APK")
                        return null
                    }

                    val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                    splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                    matchingApkEntries.add(baseApkEntry)

                    val filters = matchingApkEntries.map { createZipFilter(context, xf, xapkChannel, it, useMemoryCache, preferApacheApiWhenPossible = true) }
                    try {
                        val baseFilter = filters.last()
                        val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                        val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                        if (consolidatedInfo != null) {
                            val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                                MultiZipFilter(matchingApkEntries.indices.map { i ->
                                    val filter = filters[i]
                                    if (filter.isSeekable) NonClosingZipFilter(filter)
                                    else createZipFilter(context, xf, xapkChannel, matchingApkEntries[i], useMemoryCache, preferApacheApiWhenPossible = true)
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
//                Log.w("AppLog", "XAPK Test Framework 7: Fast path failed, using slow ZipInputStream path")
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
                            splitApkNamesList.add(entry.name to apkInfo)
                        }
                    }

                    if (baseApkName == null || packageName == null) {
                        Log.e("AppLog", "XAPK Test Framework 7 (Slow): Failed to find base APK")
                        return null
                    }

                    val matchingApkNames = ArrayList<String>(splitApkNamesList.size + 1)
                    splitApkNamesList.forEach { matchingApkNames.add(it.first) }
                    matchingApkNames.add(baseApkName)

                    val createSlowFilter = { name: String ->
                        val stream = inputStreamProvider().let { ins ->
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
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test Framework 7: Error", e)
        } finally {
            xapkFile.closeSilently()
            xapkChannel.close()
        }
        return result
    }

    private fun createZipFilter(context: Context, xapkFile: ZipFile, xapkChannel: SeekableByteChannel, entry: ZipArchiveEntry, useMemoryCache: Boolean, preferApacheApiWhenPossible: Boolean): AbstractZipFilter {
        val useApacheApi = Build.VERSION.SDK_INT >=  Build.VERSION_CODES.O && preferApacheApiWhenPossible
        if (useApacheApi) {
            if (useMemoryCache) {
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

            if (entry.method == ZipArchiveEntry.STORED) {
                try {
                    val segmentChannel = BoundedSeekableByteChannel(xapkChannel, entry.dataOffset, entry.size)
                    val innerApkFile = try {
                        ZipFile.builder().setSeekableByteChannel(segmentChannel).get()
                    } catch (e: Throwable) {
                        null
                    }
                    if (innerApkFile != null) {
                        return ApacheZipFileFilter(context, innerApkFile, underlyingChannel = segmentChannel)
                    }
                } catch (e: Throwable) {
                }
            }
            return ApacheZipArchiveInputStreamFilter(ZipArchiveInputStream(xapkFile.getInputStream(entry)))
        }

        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }
}
