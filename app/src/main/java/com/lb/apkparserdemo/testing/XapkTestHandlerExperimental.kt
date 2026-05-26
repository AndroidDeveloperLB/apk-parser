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
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
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
     * On API 24/25, it uses java.util.zip.ZipFile which is also fast and robust.
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
     * Workaround 1: Memory-based path.
     * On API 26+, uses Apache ZipFile on memory buffer.
     * On API 24/25, it patches the ZIP flags in memory and uses ZipInputStream.
     */
    fun runTestMemory(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        Log.d("AppLog", "XAPK Experimental: Started Memory Path")
        val fileSize = xapkFileOnDisk.length()
        
        if (!MemoryUtils.isEnoughMemoryForApkParsing(fileSize)) {
            Log.e("AppLog", "XAPK Experimental: Not enough memory for $fileSize bytes")
            return null
        }

        val bytes = ByteArray(fileSize.toInt())
        try {
            FileInputStream(xapkFileOnDisk).use { it.readBytesIntoByteArray(bytes) }
        } catch (e: Exception) {
            return null
        }

        val useApache = Build.VERSION.SDK_INT >= 26 && preferApacheApiWhenPossible
        return if (useApache) {
            runTestWithApacheZip(SeekableInMemoryByteChannel(bytes), deviceConfig, appIconSize)
        } else {
            // PATCH the problematic flags in memory before parsing
            MemoryUtils.patchZipBytesForOldAndroid(bytes)
            runTestSlowPath(ByteArrayInputStream(bytes), deviceConfig, appIconSize)
        }
    }

    /**
     * Standard [java.util.zip.ZipFile] implementation. 
     * This is highly robust on API 24/25 and avoids the EXT descriptor issue.
     */
    private fun runTestFrameworkZipFile(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        Log.d("AppLog", "XAPK Experimental: Started Framework ZipFile Path")
        var result: ApkParsingResult? = null
        try {
            java.util.zip.ZipFile(xapkFileOnDisk).use { xapk ->
                var baseApkEntry: java.util.zip.ZipEntry? = null
                var packageName: String? = null
                var versionCode: Long? = null
                val entries = xapk.entries()
                val splitApkNamesList = mutableListOf<String>()
                
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
                    } else {
                        splitApkNamesList.add(entry.name)
                    }
                }
                
                if (baseApkEntry == null || packageName == null) return null

                val matchingApkNames = (splitApkNamesList + baseApkEntry.name).toMutableList()

                // Nested APKs: also use ZipFile logic if possible by extracting to memory
                val filters = matchingApkNames.map { name ->
                    val entry = xapk.getEntry(name)
                    val bytes = xapk.getInputStream(entry).use { it.readBytes() }
                    // On API 24/25, we MUST NOT use Apache ZipFile here either.
                    if (Build.VERSION.SDK_INT >= 26) {
                        val channel = SeekableInMemoryByteChannel(bytes)
                        val innerZipFile = ZipFile.builder().setSeekableByteChannel(channel).get()
                        ApacheZipFileFilter(context, innerZipFile, underlyingChannel = channel)
                    } else {
                        MemoryUtils.patchZipBytesForOldAndroid(bytes)
                        ZipInputStreamFilter(ZipInputStream(ByteArrayInputStream(bytes)))
                    }
                }
                
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(filters.map { NonClosingZipFilter(it) })
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
                var versionCode: Long? = null
                val entries = xapk.entries
                val splitApkEntriesList = ArrayList<Pair<ZipArchiveEntry, ApkManifestParser.SimpleApkInfo>>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                        continue
                    }
                    val apkInfo = xapk.getInputStream(entry).use {
                        ApkManifestParser.findAndParseManifest(it, preferApacheApiWhenPossible = true)
                    } ?: continue
                    if (!apkInfo.isSplit) {
                        baseApkEntry = entry
                        packageName = apkInfo.packageName
                        versionCode = apkInfo.versionCode
                    } else {
                        splitApkEntriesList.add(Pair(entry, apkInfo))
                    }
                }
                if (baseApkEntry == null || packageName == null) return null

                val matchingApkEntries = (splitApkEntriesList.map { it.first } + baseApkEntry).toMutableList()

                val filters = matchingApkEntries.map { entry ->
                    if (entry.method == ZipArchiveEntry.STORED) {
                        val segmentChannel = BoundedSeekableByteChannel(channel, entry.dataOffset, entry.size)
                        val innerApkFile = ZipFile.builder().setSeekableByteChannel(segmentChannel).get()
                        ApacheZipFileFilter(context, innerApkFile, underlyingChannel = segmentChannel)
                    } else {
                        val bytes = xapk.getInputStream(entry).use { it.readBytes() }
                        val innerChannel = SeekableInMemoryByteChannel(bytes)
                        val innerApkFile = ZipFile.builder().setSeekableByteChannel(innerChannel).get()
                        ApacheZipFileFilter(context, innerApkFile, underlyingChannel = innerChannel)
                    }
                }
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(filters.map { NonClosingZipFilter(it) })
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

    private fun runTestSlowPath(inputStream: InputStream, deviceConfig: DeviceConfig, appIconSize: Int): ApkParsingResult? {
        var result: ApkParsingResult? = null
        try {
            ZipInputStream(inputStream).use { zis ->
                var baseApkName: String? = null
                var packageName: String? = null
                var versionCode: Long? = null
                val splitApkNamesList = mutableListOf<String>()

                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                        continue
                    }
                    val apkInfo = ApkManifestParser.findAndParseManifest(zis) ?: continue
                    if (!apkInfo.isSplit) {
                        baseApkName = entry.name
                        packageName = apkInfo.packageName
                        versionCode = apkInfo.versionCode
                    } else {
                        splitApkNamesList.add(entry.name)
                    }
                }

                if (baseApkName == null || packageName == null) return null
                val matchingApkNames = splitApkNamesList + baseApkName

                // For slow path fallback, we extract entries to memory and patch them if needed
                val filters = matchingApkNames.map { name ->
                    // This is inefficient but necessary for sequential stream fallback
                    // In real app, you'd reset the stream and skip.
                    // Here we just show the logic.
                    val bytes = readEntryBytes(inputStream, name)
                    MemoryUtils.patchZipBytesForOldAndroid(bytes)
                    ZipInputStreamFilter(ZipInputStream(ByteArrayInputStream(bytes)))
                }
                
                try {
                    val baseFilter = filters.last()
                    val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                    val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)
                    if (consolidatedInfo != null) {
                        val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                            MultiZipFilter(filters.map { NonClosingZipFilter(it) })
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

    private fun readEntryBytes(inputStream: InputStream, targetName: String): ByteArray {
        // Resetting stream for this is hard, usually you'd recreate it.
        // This is just a placeholder to show the logic.
        return ByteArray(0)
    }
}
