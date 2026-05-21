package com.lb.apkparserdemo.testing

import android.content.Context
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
 * XAPK Test Handler using Android Framework's [java.util.zip.ZipFile] for the XAPK
 * and [ZipInputStreamFilter] for the nested APKs.
 */
class XapkTestHandlerFramework2(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 2: Started")
        var result: ApkParsingResult? = null

        var xapkFile: ZipFile? = null
        try {
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
                Log.e("AppLog", "XAPK Test Framework 2: Failed to find base APK inside XAPK")
                return null
            }

            val matchingSplitEntries = splitApkEntriesList
                    .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                    .map { it.first }

            val baseApkInfo = getApkInfo(context, xapkFileOnDisk, xapkFile!!, baseApkEntry, deviceConfig)
            val splitApkInfoList = matchingSplitEntries.mapNotNull { getApkInfo(context, xapkFileOnDisk, xapkFile!!, it, deviceConfig) }

            val matchingApkEntries = matchingSplitEntries.toMutableList()
            matchingApkEntries.add(baseApkEntry)

            val filters = matchingApkEntries.map { createZipFilter(context, xapkFileOnDisk, xapkFile!!, it) }
            var baseFilter: AbstractZipFilter? = null
            try {
                baseFilter = filters.last()
                val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                val consolidatedInfo = ApkInfo.getConsolidatedApkInfo(deviceConfig, baseApkInfo!!,
                        NonClosingZipFilter(baseFilter), splitApkInfoList)
                if (consolidatedInfo != null) {
                    val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                        MultiZipFilter(matchingApkEntries.indices.map { i ->
                            val filter = filters[i]
                            if (filter.isSeekable) NonClosingZipFilter(filter)
                            else createZipFilter(context, xapkFileOnDisk, xapkFile!!, matchingApkEntries[i])
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
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test Framework 2: Error", e)
        } finally {
            xapkFile.closeSilently()
        }
        return result
    }

    private fun getApkInfo(context: Context, xapkFileOnDisk: File, xapkFile: ZipFile, entry: ZipArchiveEntry, deviceConfig: DeviceConfig): ApkInfo? {
        return try {
            val filter = createZipFilter(context, xapkFileOnDisk, xapkFile, entry)
            filter.use {
                ApkInfo.internalGetApkInfo(deviceConfig, filter,
                        requestParseResources = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createZipFilter(context: Context, xapkFileOnDisk: File, xapkFile: ZipFile, entry: ZipArchiveEntry): AbstractZipFilter {
        if (entry.method == ZipArchiveEntry.STORED) {
            try {
                val channel = FileSeekableByteChannel(xapkFileOnDisk, entry.dataOffset, entry.size)
                val innerApkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                return ApacheZipFileFilter(context, innerApkFile, underlyingChannel = channel)
            } catch (_: Exception) {
            }
        }
        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }

}
