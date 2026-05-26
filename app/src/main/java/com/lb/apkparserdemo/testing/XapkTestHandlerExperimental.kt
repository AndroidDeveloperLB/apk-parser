package com.lb.apkparserdemo.testing

import android.content.Context
import android.os.Build
import android.util.Log
import com.lb.apkparserdemo.apk_info.*
import com.lb.apkparserdemo.apk_info.zip.BoundedSeekableByteChannel
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import com.lb.apkparserdemo.apk_info.zip.SeekableInputStreamByteChannel
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.*
import java.nio.channels.SeekableByteChannel

/**
 * Experimental XAPK Test Handler implementing custom random-access workarounds
 * for API 24/25 header issues.
 */
class XapkTestHandlerExperimental(private val context: Context) {

    /**
     * Fastest available method for local files.
     */
    fun runTestOnFile(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        val useApache = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
        return if (useApache) {
            runTestWithApacheZip(FileSeekableByteChannel(xapkFileOnDisk), deviceConfig, appIconSize)
        } else {
            runTestMinimalMemory(xapkFileOnDisk.length(), deviceConfig, appIconSize) {
                FileInputStream(xapkFileOnDisk)
            }
        }
    }

    /**
     * Low-memory workaround for streams (URIs).
     * Builds an index of the ZIP first to avoid sequential header bugs.
     */
    fun runTestMinimalMemory(fileSize: Long, deviceConfig: DeviceConfig, appIconSize: Int, inputStreamProvider: () -> InputStream): ApkParsingResult? {
        Log.d("AppLog", "XAPK Experimental: Started Minimal Memory Path")
        val xapkChannel = object : SeekableInputStreamByteChannel(fileSize) {
            override fun getNewInputStream(): InputStream = inputStreamProvider()
        }
        return try {
            runTestWithCustomIndexer(xapkChannel, deviceConfig, appIconSize)
        } finally {
            xapkChannel.close()
        }
    }

    private fun runTestWithCustomIndexer(channel: SeekableByteChannel, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Experimental: Using Custom Indexer Path")
        var result: ApkParsingResult? = null
        try {
            val index = ZipIndexer.createIndex(channel)
            val xapkFilter = SeekableZipFilter(context, channel, index)

            var baseApkName: String? = null
            var packageName: String? = null
            val splitApkNamesList = mutableListOf<String>()
            
            // Check if it's a single APK renamed to XAPK or a merged XAPK
            if (index.containsKey("AndroidManifest.xml")) {
                Log.d("AppLog", "XAPK Experimental: Single APK detected (already indexed)")
                return parseConsolidated(deviceConfig, xapkFilter, emptyList(), appIconSize) { xapkFilter }
            }

            // Find APK names and parse manifests using the index
            for (name in xapkFilter.allEntryNames) {
                if (!name.endsWith(".apk", ignoreCase = true) || name.contains("/")) continue
                val innerFilter = createFilterForInnerApk(xapkFilter, name) ?: continue
                try {
                    val apkInfo = innerFilter.getByteArrayForEntries(hashSetOf("AndroidManifest.xml"))?.get("AndroidManifest.xml")?.let {
                        ApkManifestParser.parseManifestInputStream(ByteArrayInputStream(it))
                    } ?: continue

                    if (!apkInfo.isSplit) {
                        baseApkName = name
                        packageName = apkInfo.packageName
                    } else splitApkNamesList.add(name)
                } finally {
                    innerFilter.close()
                }
            }

            if (baseApkName == null || packageName == null) return null
            val matchingApkNames = (splitApkNamesList + baseApkName).toMutableList()

            result = parseConsolidated(deviceConfig, createFilterForInnerApk(xapkFilter, baseApkName)!!, 
                splitApkNamesList.map { createFilterForInnerApk(xapkFilter, it)!! }, appIconSize) {
                MultiZipFilter(matchingApkNames.map { createFilterForInnerApk(xapkFilter, it)!! })
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Experimental: Custom Indexer error", e)
        }
        return result
    }

    private fun parseConsolidated(
        deviceConfig: DeviceConfig,
        baseFilter: AbstractZipFilter,
        extraFilters: List<AbstractZipFilter>,
        appIconSize: Int,
        multiFilterGenerator: () -> AbstractZipFilter
    ): ApkParsingResult? {
        try {
            val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters.map { NonClosingZipFilter(it) }, requestParseResources = true)
            if (consolidatedInfo != null) {
                val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                    multiFilterGenerator()
                }, consolidatedInfo, appIconSize)
                val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
                return ApkParsingResult(apkMeta.packageName, apkMeta.versionCode, apkMeta.versionName, apkMeta.label, apkIcon)
            }
        } finally {
            baseFilter.close()
            extraFilters.forEach { it.close() }
        }
        return null
    }

    private fun createFilterForInnerApk(xapkFilter: SeekableZipFilter, name: String): AbstractZipFilter? {
        val entryInfo = xapkFilter.getEntryInfo(name) ?: return null
        return if (entryInfo.method == 0) { // STORED
            val innerChannel = xapkFilter.getChannelForEntry(name) ?: return null
            val innerIndex = ZipIndexer.createIndex(innerChannel)
            SeekableZipFilter(context, innerChannel, innerIndex)
        } else {
            // Case: Inner APK is DEFLATED.
            // On API < 26, Apache ZipFile is broken. We use a nested seekable stream channel.
            // This channel allows "seeking" by re-creating the stream from the XAPK and skipping.
            val innerChannel = object : SeekableInputStreamByteChannel(entryInfo.uncompressedSize) {
                override fun getNewInputStream(): InputStream = xapkFilter.getInputStreamForEntry(name)!!
            }
            val innerIndex = ZipIndexer.createIndex(innerChannel)
            SeekableZipFilter(context, innerChannel, innerIndex)
        }
    }

    private fun runTestWithApacheZip(channel: SeekableByteChannel, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        var result: ApkParsingResult? = null
        try {
            ZipFile.builder().setSeekableByteChannel(channel).get().use { xapk ->
                var baseApkEntry: ZipArchiveEntry? = null
                var packageName: String? = null
                val splitApkEntriesList = mutableListOf<ZipArchiveEntry>()
                val entries = xapk.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) continue
                    val apkInfo = xapk.getInputStream(entry).use { ApkManifestParser.findAndParseManifest(it, preferApacheApiWhenPossible = true) } ?: continue
                    if (!apkInfo.isSplit) {
                        baseApkEntry = entry
                        packageName = apkInfo.packageName
                    } else splitApkEntriesList.add(entry)
                }
                if (baseApkEntry == null || packageName == null) return null
                val matchingApkEntries = (splitApkEntriesList + baseApkEntry).toMutableList()

                val createFilter = { entry: ZipArchiveEntry ->
                    if (entry.method == ZipArchiveEntry.STORED) {
                        val segmentChannel = BoundedSeekableByteChannel(channel, entry.dataOffset, entry.size)
                        ApacheZipFileFilter(context, ZipFile.builder().setSeekableByteChannel(segmentChannel).get(), underlyingChannel = segmentChannel)
                    } else {
                        ApacheZipArchiveInputStreamFilter(ZipArchiveInputStream(xapk.getInputStream(entry)))
                    }
                }

                val filters = matchingApkEntries.map { createFilter(it) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)
                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.map { createFilter(it) })
                        }, consolidatedInfo, appIconSize)
                        val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
                        result = ApkParsingResult(apkMeta.packageName, apkMeta.versionCode, apkMeta.versionName, apkMeta.label, apkIcon)
                    }
                } finally {
                    filters.forEach { it.close() }
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Experimental: Apache error", e)
        }
        return result
    }
}
