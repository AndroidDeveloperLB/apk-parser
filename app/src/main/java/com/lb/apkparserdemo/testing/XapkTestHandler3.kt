package com.lb.apkparserdemo.testing

import android.content.Context
import android.os.Build
import android.util.Log
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipArchiveInputStreamFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkManifestParser
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.ZipInputStreamFilter
import net.dongliu.apk.parser.bean.DeviceConfig
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XAPK Test Handler that reuses the XAPK handle and uses sequential scans with inclusion logic.
 * Uses [ZipInputStreamFilter] for processing nested APKs as streams.
 */
class XapkTestHandler3(private val context: Context) {

    fun runTest(xapkFileOnDisk: File, deviceConfig: DeviceConfig, appIconSize: Int, preferApacheApiWhenPossible: Boolean = true): ApkParsingResult? {
        Log.d("AppLog", "XAPK Test 3: Started")
        var result: ApkParsingResult? = null
        try {
            val useApacheApi = Build.VERSION.SDK_INT >=  Build.VERSION_CODES.O && preferApacheApiWhenPossible
            if (useApacheApi) {
                val xapk = ZipFile.builder().setFile(xapkFileOnDisk).get()
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
                        Log.e("AppLog", "XAPK Test 3: Failed to find base APK")
                        return null
                    }

                    val matchingApkEntries = ArrayList<ZipArchiveEntry>(splitApkEntriesList.size + 1)
                    splitApkEntriesList.forEach { matchingApkEntries.add(it.first) }
                    matchingApkEntries.add(baseApkEntry)

                    val filters = matchingApkEntries.map { createZipFilter(xapkFile, it, preferApacheApiWhenPossible = true) }
                    try {
                        val baseFilter = filters.last()
                        val extraFilters = filters.dropLast(1).map { NonClosingZipFilter(it) }
                        val consolidatedInfo = ApkInfo.internalGetApkInfo(deviceConfig, NonClosingZipFilter(baseFilter), extraFilters, requestParseResources = true)

                        if (consolidatedInfo != null) {
                            val apkIcon = ApkIconFetcher.getApkIcon(context, deviceConfig, {
                                MultiZipFilter(matchingApkEntries.map { createZipFilter(xapkFile, it, preferApacheApiWhenPossible = true) })
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
            } else {
//                Log.w("AppLog", "XAPK Test 3: Fast path failed, using slow path")
                java.io.FileInputStream(xapkFileOnDisk).use { fis ->
                    ZipInputStream(fis).use { zis ->
                        var baseApkNameSlow: String? = null
                        var packageNameSlow: String? = null
                        var versionCodeSlow: Long? = null
                        val splitApkNamesList = ArrayList<Pair<String, ApkManifestParser.SimpleApkInfo>>()
                        while (true) {
                            val entry = zis.nextEntry ?: break
                            if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true) || entry.name.contains("/")) {
                                continue
                            }
                            val apkInfo = ApkManifestParser.findAndParseManifest(zis, preferApacheApiWhenPossible = false) ?: continue
                            if (!apkInfo.isSplit) {
                                baseApkNameSlow = entry.name
                                packageNameSlow = apkInfo.packageName
                                versionCodeSlow = apkInfo.versionCode
                                splitApkNamesList.removeAll {
                                    it.second.packageName != packageNameSlow || it.second.versionCode != versionCodeSlow
                                }
                            } else {
                                if ((packageNameSlow != null && apkInfo.packageName != packageNameSlow) || (versionCodeSlow != null && apkInfo.versionCode != versionCodeSlow))
                                    continue
                                splitApkNamesList.add(Pair(entry.name, apkInfo))
                            }
                        }
                        if (baseApkNameSlow == null || packageNameSlow == null) {
                            Log.e("AppLog", "XAPK Test 3 (Slow): Failed to find base APK")
                            return null
                        }
                        val matchingApkNames = ArrayList<String>(splitApkNamesList.size + 1)
                        splitApkNamesList.forEach { matchingApkNames.add(it.first) }
                        matchingApkNames.add(baseApkNameSlow)
                        val createSlowFilter = { name: String ->
                            val stream = java.io.FileInputStream(xapkFileOnDisk).let { ins ->
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
            }
        } catch (e: Exception) {
            Log.e("AppLog", "XAPK Test 3: Error", e)
        }
        return result
    }

    private fun createZipFilter(xapkFile: ZipFile, entry: ZipArchiveEntry, preferApacheApiWhenPossible: Boolean): AbstractZipFilter {
        val useApacheApi = Build.VERSION.SDK_INT >=  Build.VERSION_CODES.O && preferApacheApiWhenPossible
        if (useApacheApi) {
            return ApacheZipArchiveInputStreamFilter(ZipArchiveInputStream(xapkFile.getInputStream(entry)))
        }
        return ZipInputStreamFilter(ZipInputStream(xapkFile.getInputStream(entry)))
    }

}
