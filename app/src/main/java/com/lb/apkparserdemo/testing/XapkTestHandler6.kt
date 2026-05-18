package com.lb.apkparserdemo.testing

import android.content.Context
import android.util.Log
import com.lb.apkparserdemo.apk_info.ApacheZipFileFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.zip.BoundedSeekableByteChannel
import com.lb.apkparserdemo.apk_info.zip.SeekableInputStreamByteChannel
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * XAPK Test Handler that reuses the XAPK handle and uses [SeekableInputStreamByteChannel]
 * to provide random access over an [InputStream].
 */
class XapkTestHandler6(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        val inputStreamProvider = { FileInputStream(xapkFileOnDisk) }
        val fileSize = xapkFileOnDisk.length()
        return runTest(deviceConfig, appIconSize) {
            object : SeekableInputStreamByteChannel(fileSize) {
                override fun getNewInputStream(): InputStream = inputStreamProvider()
            }
        }
    }

    fun runTest(deviceConfig: DeviceConfig, appIconSize: Int, channelProvider: () -> SeekableByteChannel): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test 6: Started")
        val xapkChannel = channelProvider()
        var result: ApkParsingResult? = null
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
                        splitApkEntriesList.add(Pair(entry, apkInfo))
                    }
                }
                if (baseApkEntry == null || packageName == null) {
                    Log.e("AppLog", "XAPK Test 6: Failed to find base APK")
                    return null
                }

                val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                matchingApkEntries.add(baseApkEntry)

                val filters = matchingApkEntries.map { createApacheZipFilter(context, xapkChannel, it) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(filters.map { NonClosingZipFilter(it) })
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
            Log.e("AppLog", "XAPK Test 6: Error", e)
        } finally {
            xapkChannel.close()
        }
        return result
    }

    private fun createApacheZipFilter(context: Context, xapkChannel: SeekableByteChannel, entry: ZipArchiveEntry): ApacheZipFileFilter {
        val channel = BoundedSeekableByteChannel(xapkChannel, entry.dataOffset, entry.size)
        val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
        return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
    }

}
