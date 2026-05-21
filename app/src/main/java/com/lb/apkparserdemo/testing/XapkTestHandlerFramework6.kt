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
import com.lb.apkparserdemo.apk_info.zip.BoundedSeekableByteChannel
import com.lb.apkparserdemo.apk_info.zip.SeekableInputStreamByteChannel
import com.lb.common_utils.closeSilently
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler using [SeekableInputStreamByteChannel] for the XAPK (allowing InputStream-based random access)
 * and [ZipInputStreamFilter] for the nested APKs.
 */
class XapkTestHandlerFramework6(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test Framework 6: Started")
        val inputStreamProvider = { FileInputStream(xapkFileOnDisk) }
        var result: ApkParsingResult? = null

        val xapkChannel = object : SeekableInputStreamByteChannel(xapkFileOnDisk.length()) {
            override fun getNewInputStream(): InputStream = inputStreamProvider()
        }

        var xapkFile: ZipFile? = null
        try {
            xapkFile = ZipFile.builder().setSeekableByteChannel(xapkChannel).get()
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
            if (baseApkEntry == null || packageName == null || versionCode == null) return null

            val matchingSplitEntries = splitApkEntriesList
                    .filter { it.second.packageName == packageName && it.second.versionCode == versionCode }
                    .map { it.first }

            val baseApkInfo = getApkInfo(context, xapkFile!!, xapkChannel, baseApkEntry, deviceConfig)
            val splitApkInfoList = matchingSplitEntries.mapNotNull { getApkInfo(context, xapkFile!!, xapkChannel, it, deviceConfig) }

            val matchingApkEntries = matchingSplitEntries.toMutableList()
            matchingApkEntries.add(baseApkEntry)

            val filters = matchingApkEntries.map { createZipFilter(context, xapkFile!!, xapkChannel, it) }
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
                            else createZipFilter(context, xapkFile!!, xapkChannel, matchingApkEntries[i])
                        })
                    }, consolidatedInfo, appIconSize)
                    val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
//                    val appLabel = apkMeta.label
//                    if (apkIcon != null)
//                        Log.d("AppLog", "XAPK Test Framework 6: Success apkIcon:${apkIcon.width}x${apkIcon.height} label:$appLabel packageName:${apkMeta.packageName} versionCode:${apkMeta.versionCode} versionName:${apkMeta.versionName}")
//                    else
//                        Log.e("AppLog", "XAPK Test Framework 6: Failed to get icon. label:$appLabel packageName:${apkMeta.packageName} versionCode:${apkMeta.versionCode} versionName:${apkMeta.versionName}")
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
            Log.e("AppLog", "XAPK Test Framework 6: Error", e)
        } finally {
            xapkFile.closeSilently()
        }
        return result
    }

    private fun getApkInfo(context: Context, xapkFile: ZipFile, xapkChannel: SeekableByteChannel, entry: ZipArchiveEntry, deviceConfig: DeviceConfig): ApkInfo? {
        return try {
            val filter = createZipFilter(context, xapkFile, xapkChannel, entry)
            filter.use {
                ApkInfo.internalGetApkInfo(deviceConfig, filter, requestParseResources = true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createZipFilter(context: Context, xapkFile: ZipFile, xapkChannel: SeekableByteChannel, entry: ZipArchiveEntry): AbstractZipFilter {
        if (entry.method == ZipArchiveEntry.STORED) {
            try {
                val channel = BoundedSeekableByteChannel(xapkChannel, entry.dataOffset, entry.size)
                val innerApkFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                return ApacheZipFileFilter(context, innerApkFile, underlyingChannel = channel)
            } catch (_: Exception) {
            }
        }
        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }
}
