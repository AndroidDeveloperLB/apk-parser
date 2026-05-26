package com.lb.apkparserdemo.testing

import android.content.Context
import android.os.Build
import android.util.Log
import com.lb.apkparserdemo.apk_info.*
import com.lb.apkparserdemo.apk_info.zip.BoundedSeekableByteChannel
import com.lb.apkparserdemo.apk_info.zip.FileSeekableByteChannel
import com.lb.common_utils.readBytesIntoByteArray
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.*
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipInputStream

/**
 * Experimental XAPK Test Handler to test workarounds for the
 * "only DEFLATED entries can have EXT descriptor" ZipException on API 24/25.
 */
class XapkTestHandlerExperimental(private val context: Context) {

    /**
     * Optimized variant that uses the best available method for a local file.
     * On API 26+, it uses Apache ZipFile for speed.
     * On API 24/25, it uses java.util.zip.ZipFile which is robust against the header issue.
     */
    fun runTestOnFile(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        val useApache = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
        return if (useApache) {
            runTestWithApacheZip(FileSeekableByteChannel(xapkFileOnDisk), deviceConfig, appIconSize)
        } else {
            runTestFrameworkZipFile(xapkFileOnDisk, deviceConfig, appIconSize)
        }
    }

    /**
     * Minimal Memory Path: Uses a stream-based parser with a header-patching wrapper.
     * This uses almost zero extra memory and works with any [InputStream] source.
     */
    fun runTestMinimalMemory(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Experimental: Started Minimal Memory Path")
        val inputStreamProvider = { FileInputStream(xapkFileOnDisk) }

        // Wrap the stream in our patcher to fix API 24/25 issues on-the-fly
        val patchedStream = MemoryUtils.ZipPatchInputStream(inputStreamProvider())
        return runTestSlowPath(patchedStream, deviceConfig, appIconSize, inputStreamProvider)
    }

    /**
     * Standard [java.util.zip.ZipFile] implementation.
     */
    private fun runTestFrameworkZipFile(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Experimental: Started Framework ZipFile Path")
        var result: ApkParsingResult? = null
        try {
            java.util.zip.ZipFile(xapkFileOnDisk).use { xapk ->
                var baseApkEntry: java.util.zip.ZipEntry? = null
                var packageName: String? = null
                val splitApkNamesList = mutableListOf<String>()

                val entries = xapk.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) continue
                    val apkInfo = xapk.getInputStream(entry).use { ApkManifestParser.findAndParseManifest(it) } ?: continue
                    if (!apkInfo.isSplit) {
                        baseApkEntry = entry
                        packageName = apkInfo.packageName
                    } else splitApkNamesList.add(entry.name)
                }

                if (baseApkEntry == null || packageName == null) return null
                val matchingApkNames = (splitApkNamesList + baseApkEntry.name).toMutableList()

                // Nested APKs: standard ZipFile provides the APK streams.
                // We wrap them in ZipPatchInputStream for API 24/25 safety.
                val createFilter = { name: String ->
                    val stream = MemoryUtils.ZipPatchInputStream(xapk.getInputStream(xapk.getEntry(name)))
                    ZipInputStreamFilter(ZipInputStream(stream))
                }

                val filters = matchingApkNames.map { createFilter(it) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkNames.map { createFilter(it) })
                        }, consolidatedInfo, appIconSize)
                        val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
                        result = ApkParsingResult(apkMeta.packageName, apkMeta.versionCode, apkMeta.versionName, apkMeta.label, apkIcon)
                    }
                } finally {
                    filters.forEach { it.close() }
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Experimental: Framework ZipFile error", e)
        }
        return result
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

                val filters = matchingApkEntries.map { entry ->
                    if (entry.method == ZipArchiveEntry.STORED) {
                        val segmentChannel = BoundedSeekableByteChannel(channel, entry.dataOffset, entry.size)
                        ApacheZipFileFilter(context, ZipFile.builder().setSeekableByteChannel(segmentChannel).get(), underlyingChannel = segmentChannel)
                    } else {
                        ApacheZipArchiveInputStreamFilter(org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(xapk.getInputStream(entry)))
                    }
                }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)
                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkEntries.map { entry ->
                                if (entry.method == ZipArchiveEntry.STORED) {
                                    val sc = BoundedSeekableByteChannel(channel, entry.dataOffset, entry.size)
                                    ApacheZipFileFilter(context, ZipFile.builder().setSeekableByteChannel(sc).get(), underlyingChannel = sc)
                                } else ApacheZipArchiveInputStreamFilter(org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(xapk.getInputStream(entry)))
                            })
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

    private fun runTestSlowPath(inputStream: InputStream, deviceConfig: DeviceConfig, appIconSize: Int, streamProvider: () -> InputStream): ApkParsingResult? {
        var result: ApkParsingResult? = null
        try {
            ZipInputStream(inputStream).use { zis ->
                var baseApkName: String? = null
                var packageName: String? = null
                val splitApkNamesList = mutableListOf<String>()

                while (true) {
                    val entry = zis.nextEntry ?: break
//                    Log.d("AppLog", "entry:${entry.name}")
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) continue
                    val apkInfo = ApkManifestParser.findAndParseManifest(zis) ?: continue
                    if (!apkInfo.isSplit) {
                        baseApkName = entry.name
                        packageName = apkInfo.packageName
                    } else splitApkNamesList.add(entry.name)
                }

                if (baseApkName == null || packageName == null) return null
                val matchingApkNames = splitApkNamesList + baseApkName

                val createFilter = { name: String ->
                    val freshStream = streamProvider()
                    val outerZis = ZipInputStream(MemoryUtils.ZipPatchInputStream(freshStream))
                    var e = outerZis.nextEntry
                    while (e != null && e.name != name) e = outerZis.nextEntry
                    ZipInputStreamFilter(ZipInputStream(outerZis))
                }

                val filters = matchingApkNames.map { createFilter(it) }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)
                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(matchingApkNames.map { createFilter(it) })
                        }, consolidatedInfo, appIconSize)
                        val apkMeta = consolidatedInfo.apkMetaTranslator.apkMeta
                        result = ApkParsingResult(apkMeta.packageName, apkMeta.versionCode, apkMeta.versionName, apkMeta.label, apkIcon)
                    }
                } finally {
                    filters.forEach { it.close() }
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Experimental: Slow path error", e)
        }
        return result
    }
}
