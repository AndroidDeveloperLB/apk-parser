package com.lb.apkparserdemo.testing

import android.content.Context
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipArchiveInputStreamFilter
import com.lb.apkparserdemo.apk_info.ApacheZipFileFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

/**
 * XAPK Test Handler using Apache Commons Compress.
 * Efficiently parses XAPK files (ZIPs containing multiple APKs) by using
 * [FileSeekableByteChannel] to access nested APK entries via random access.
 */
class XapkTestHandler(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test: Started")
        var result: ApkParsingResult? = null
        try {
            ZipFile.builder().setFile(xapkFileOnDisk).get().use { xapk: ZipFile ->
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
                if (baseApkEntry == null || packageName == null || versionCode == null) {
                    Log.e("AppLog", "XAPK Test: Failed to find base APK")
                    return null
                }

                val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                matchingApkEntries.add(baseApkEntry)

                val filters = matchingApkEntries.map { createApacheZipFilter(context, xapkFileOnDisk, xapk, it) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.indices.map { i ->
                                val filter = filters[i]
                                if (filter.isSeekable) NonClosingZipFilter(filter)
                                else createApacheZipFilter(context, xapkFileOnDisk, xapk, matchingApkEntries[i])
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
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test: Error", e)
        }
        return result
    }

    private fun createApacheZipFilter(context: Context, xapkFileOnDisk: File, xapk: ZipFile, entry: ZipArchiveEntry): AbstractZipFilter {
        if (entry.method == ZipArchiveEntry.STORED) {
            try {
                val channel = FileSeekableByteChannel(xapkFileOnDisk, entry.dataOffset, entry.size)
                val apkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                return ApacheZipFileFilter(context, apkFile, underlyingChannel = channel)
            } catch (_: Exception) {
            }
        }
        return ApacheZipArchiveInputStreamFilter(ZipArchiveInputStream(xapk.getInputStream(entry)))
    }

}
