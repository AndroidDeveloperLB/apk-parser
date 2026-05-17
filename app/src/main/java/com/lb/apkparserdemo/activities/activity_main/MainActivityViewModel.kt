package com.lb.apkparserdemo.activities.activity_main

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.UiThread
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewModelScope
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipFileFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.ZipFileFilter
import com.lb.apkparserdemo.apk_info.zip.SeekableInputStreamByteChannel
import com.lb.apkparserdemo.db.AppDatabase
import com.lb.apkparserdemo.db.AppIconInfo
import com.lb.apkparserdemo.testing.XapkTestHandler6
import com.lb.apkparserdemo.utils.AppInfoUtil
import com.lb.apkparserdemo.utils.IconStorage
import com.lb.apkparserdemo.utils.SessionTracker
import com.lb.apkparserdemo.utils.getInstalledPackagesCompat
import com.lb.apkparserdemo.utils.isSystemApp
import com.lb.common_utils.BaseViewModel
import com.lb.common_utils.MutableLiveData2
import com.lb.common_utils.closeSilently
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import net.dongliu.apk.parser.bean.DeviceConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val VALIDATE_RESOURCES = true
private val ZIP_FILTER_TYPE: MainActivityViewModel.Companion.ZipFilterType =
//time taken(ms): 35523 . handled 416 apps, apksCount:854 averageTime(ms):85.39183 per app, 41.59602 per APK
        MainActivityViewModel.Companion.ZipFilterType.ZipFileFilter
//time taken(ms):  700264 . handled 416 apps, apksCount:854 averageTime(ms):1683.3269 per app, 819.98126 per APK
//        MainActivityViewModel.Companion.ZipFilterType.ZipInputStreamFilter
//
//        MainActivityViewModel.Companion.ZipFilterType.ApacheZipArchiveInputStreamFilter
//time taken(ms): 83010 . handled 416 apps, apksCount:854 averageTime(ms):199.54327 per app, 97.20141 per APK
//MainActivityViewModel.Companion.ZipFilterType.ApacheZipFileFilter
//        time taken(ms): 98413 . handled 416 apps, apksCount:854 averageTime(ms):236.56972 per app, 115.2377 per APK
//     MainActivityViewModel.Companion.ZipFilterType.SeekableInputStreamFilter

class MainActivityViewModel(application: Application) : BaseViewModel(application) {
    val appsHandledLiveData = CounterMutableLiveData()
    val apkFilesHandledLiveData = CounterMutableLiveData()
    val frameworkErrorsOfApkTypeLiveData = CounterMutableLiveData()
    val parsingErrorsLiveData = CounterMutableLiveData()
    val wrongApkTypeErrorsLiveData = CounterMutableLiveData()
    val wrongPackageNameErrorsLiveData = CounterMutableLiveData()
    val failedGettingAppIconErrorsLiveData = CounterMutableLiveData()
    val wrongLabelErrorsLiveData = CounterMutableLiveData()
    val wrongVersionCodeErrorsLiveData = CounterMutableLiveData()
    val wrongVersionNameErrorsLiveData = CounterMutableLiveData()
    val systemAppsErrorsCountLiveData = CounterMutableLiveData()
    val isDoneLiveData = MutableLiveData2(false)

    private val db = AppDatabase.getDatabase(application)
    private val appIconDao = db.appIconDao()

    private var fetchAppInfoJob: Job? = null
    private val fetchAppInfoDispatcher: CoroutineDispatcher =
            Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    @UiThread
    fun init() {
        if (fetchAppInfoJob != null) return
        fetchAppInfoJob = viewModelScope.launch(fetchAppInfoDispatcher) {
            performTests()
        }
    }

    private fun copyRawToFile(context: Context, resourceId: Int, file: File) {
        if (!file.exists() || file.length() == 0L) {
            context.resources.openRawResource(resourceId).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private suspend fun performTests() {
        SessionTracker.clear()
        appIconDao.deleteAll()
        IconStorage.clearCache(applicationContext)
        val config = applicationContext.resources.configuration
        val localeList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = config.locales
            val result = mutableListOf<Locale>()
            for (i in 0 until list.size()) {
                result.add(list.get(i))
            }
            result
        } else {
            @Suppress("DEPRECATION")
            listOf(config.locale)
        }
        val mainLocale = localeList.firstOrNull()
        val densityDpi = applicationContext.resources.displayMetrics.densityDpi
        val uiMode = applicationContext.resources.configuration.uiMode
        val deviceConfig = DeviceConfig.create(mainLocale, config.mcc, config.mnc, densityDpi, uiMode)
        val context = applicationContext
        val appIconSize = AppInfoUtil.getAppIconSize(context)

//        testXapkParsing(deviceConfig, appIconSize)
        testInstalledAppsApks(deviceConfig, appIconSize)
        Log.e("AppLog", "done")
        isDoneLiveData.postValue(true)
    }

    private suspend fun testXapkParsing(deviceConfig: DeviceConfig, appIconSize: Int) {
        val context = applicationContext
        val packageManager = context.packageManager
        val appsToFocusOn = HashSet<String>()
                .also {
                    //a lot of memory:
//                                    it.add("com.google.android.youtube")
//                    it.add("com.google.android.googlequicksearchbox")

//                    has image file of spr file format:
//                    it.add("com.samsung.advp.imssettings")//ImsSettings.apk
//                    it.add("com.android.nfc")//NfcNci.apk

//                    has bmp files that can't be opened even on Windows OS:
//                    it.add("com.samsung.crane")//Crane.apk
                    //                    it.add("com.sec.android.widgetapp.easymodecontactswidget") //EasymodeContactsWidget81.apk
                }
        val installedPackages = packageManager.getInstalledPackagesCompat(PackageManager.GET_META_DATA)
                .filter { appsToFocusOn.isEmpty() || appsToFocusOn.contains(it.packageName) }
//                .filter {
//                    val size = it.applicationInfo?.splitPublicSourceDirs?.size
//                            ?: return@filter true
//                    size==0
//                }
        val useMemCache: Boolean = false
        val xapkFile = File(context.cacheDir, "test.xapk")
//        copyRawToFile(context, R.raw.test, xapkFile)
        var totalParsingTime = 0L
        var apksHandledSoFar = 0
        for (packageInfo in installedPackages) {
            prepareXapkFile(packageInfo, xapkFile)
            val stepStartTime = System.currentTimeMillis()
            val result: ApkParsingResult? = try {
                // XAPK test
                //2218 time taken(ms): 61754 . handled 224 apps, apksCount:245 averageTime(ms):275.6875 per app, 252.05714 per APK
//                com.lb.apkparserdemo.testing.XapkTestHandler(context).runTest(xapkFile, deviceConfig, appIconSize)
                //2036 time taken(ms): 66864 . handled 224 apps, apksCount:245 averageTime(ms):298.5 per app, 272.91428 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandler2(context).runTest(xapkFile, deviceConfig, appIconSize)
                //2244 time taken(ms): 54330 . handled 224 apps, apksCount:245 averageTime(ms):242.54465 per app, 221.7551 per APK
//                com.lb.apkparserdemo.testing.XapkTestHandler3(context).runTest(xapkFile, deviceConfig, appIconSize)
                //  2042   without memcache, 1710    with memcache
//                time taken(ms): 67660 . handled 224 apps, apksCount:245 averageTime(ms):302.05356 per app, 276.16327 per APK
//time taken(ms): 62793 . handled 224 apps, apksCount:245 averageTime(ms):280.3259 per app, 256.29797 per APK
//                com.lb.apkparserdemo.testing.XapkTestHandler4(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache)
                // 1968   without memcache,  1729    with memcache
//                time taken(ms): 67053 . handled 224 apps, apksCount:245 averageTime(ms):299.34375 per app, 273.6857 per APK
//time taken(ms): 62721 . handled 224 apps, apksCount:245 averageTime(ms):280.00446 per app, 256.0041 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandler5(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache)
                //time taken(ms): 37436 . handled 224 apps, apksCount:245 averageTime(ms):167.125 per app, 152.8 per APK
//                time taken(ms): 57490 . handled 415 apps, apksCount:848 averageTime(ms):138.53012 per app, 67.794815 per APK
                XapkTestHandler6(context).runTest(xapkFile, deviceConfig, appIconSize)
                //  2293 without memcache,  1683    with memcache
//                time taken(ms): 61024 . handled 224 apps, apksCount:245 averageTime(ms):272.42856 per app, 249.07755 per APK
//time taken(ms): 59681 . handled 224 apps, apksCount:245 averageTime(ms):266.43304 per app, 243.59592 per APK
//                time taken(ms): 148577 . handled 415 apps, apksCount:848 averageTime(ms):358.01688 per app, 175.20872 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandler7(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache)

                //4085 time taken(ms): 69288 . handled 224 apps, apksCount:245 averageTime(ms):309.32144 per app, 282.80817 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework1(context).runTest(xapkFile, deviceConfig, appIconSize)
                //4092 time taken(ms): 68775 . handled 224 apps, apksCount:245 averageTime(ms):307.03125 per app, 280.7143 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework2(context).runTest(xapkFile, deviceConfig, appIconSize)
                //4088 time taken(ms): 69418 . handled 224 apps, apksCount:245 averageTime(ms):309.9018 per app, 283.33878 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework3(context).runTest(xapkFile, deviceConfig, appIconSize)
                //  4162  without memcache,  1439    with memcache
//                 time taken(ms): 67375 . handled 224 apps, apksCount:245 averageTime(ms):300.78125 per app, 275.0 per APK
//time taken(ms): 65055 . handled 224 apps, apksCount:245 averageTime(ms):290.4241 per app, 265.5306 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework4(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache)
                //   1996  without memcache, 1702     with memcache
//                time taken(ms): 67274 . handled 224 apps, apksCount:245 averageTime(ms):300.33035 per app, 274.58777 per APK
//time taken(ms): 63762 . handled 224 apps, apksCount:245 averageTime(ms):284.6518 per app, 260.25305 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework5(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache)
                //1750 time taken(ms): 62696 . handled 224 apps, apksCount:245 averageTime(ms):279.89285 per app, 255.90204 per APK
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework6(context).runTest(xapkFile, deviceConfig, appIconSize)
                // 1985    without memcache,   1748   with memcache
//                time taken(ms): 60809 . handled 224 apps, apksCount:245 averageTime(ms):271.46875 per app, 248.2 per APK
//time taken(ms): 55884 . handled 224 apps, apksCount:245 averageTime(ms):249.48215 per app, 228.09796 per APK
//                XapkTestHandlerFramework7(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache)
            } catch (e: Throwable) {
                Log.e("AppLog", "testXapkParsing error for ${packageInfo.packageName}", e)
                null
            }
            totalParsingTime += System.currentTimeMillis() - stepStartTime
            handleParsingResult(packageInfo, result, appIconSize)
            apksHandledSoFar += 1 + (packageInfo.applicationInfo?.splitPublicSourceDirs?.size ?: 0)
            apkFilesHandledLiveData.postValue(apksHandledSoFar)
            appsHandledLiveData.inc()
//            break
        }
        xapkFile.delete()
        Log.d("AppLog", "time taken(ms): $totalParsingTime . handled ${installedPackages.size} apps, apksCount:$apksHandledSoFar averageTime(ms):${totalParsingTime.toFloat() / installedPackages.size.toFloat()} per app, ${totalParsingTime.toFloat() / apksHandledSoFar.toFloat()} per APK")
    }

    private suspend fun handleParsingResult(
            packageInfo: PackageInfo,
            result: ApkParsingResult?,
            appIconSize: Int
    ) {
        val packageName = packageInfo.packageName
        val isSystemApp = packageInfo.isSystemApp()
        if (result == null) {
            parsingErrorsLiveData.inc()
            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
            Log.e("AppLog", "can't parse apk for \"$packageName\"")
            return
        }
        val context = applicationContext
        val packageManager = context.packageManager
        val densityDpi = context.resources.displayMetrics.densityDpi
        if (packageName != result.packageName) {
            wrongPackageNameErrorsLiveData.inc()
            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
            Log.e("AppLog", "apk package name is different for $packageName : correct one is: \"$packageName\" vs found: \"${result.packageName}\" isSystemApp?$isSystemApp")
        }

        if (packageInfo.versionName != result.versionName) {
            wrongVersionNameErrorsLiveData.inc()
            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
            Log.e("AppLog", "apk version name is different for \"$packageName\" : correct one is: \"${packageInfo.versionName}\" vs found: \"${result.versionName}\" isSystemApp?$isSystemApp")
        }

        if (versionCodeCompat(packageInfo) != result.versionCode) {
            wrongVersionCodeErrorsLiveData.inc()
            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
            Log.e("AppLog", "apk version code is different for \"$packageName\" : correct one is: ${versionCodeCompat(packageInfo)} vs found: ${result.versionCode} isSystemApp?$isSystemApp")
        }

        val labelOfLibrary = result.label ?: result.packageName
        val labelOfLibraryString = labelOfLibrary.toString()
        val expectedAppLabel = packageInfo.applicationInfo!!.loadLabel(packageManager)
        if (expectedAppLabel != labelOfLibraryString) {
            wrongLabelErrorsLiveData.inc()
            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
            Log.e("AppLog", "label mismatch for \"${packageName}\": correct=\"$expectedAppLabel\" vs found=\"$labelOfLibrary\"")
        }

        val appIcon = result.icon
        if (packageInfo.applicationInfo!!.icon != 0 && appIcon == null) {
            failedGettingAppIconErrorsLiveData.inc()
            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
            Log.e("AppLog", "fetching error: can't get app icon for \"$packageName\"")
        }

        if (appIcon != null) {
            val lastUpdateTime = packageInfo.lastUpdateTime
            val cachedInfo = appIconDao.getByPackageName(packageName)
            if (cachedInfo == null || cachedInfo.lastUpdateTime != lastUpdateTime) {
                if (cachedInfo != null) {
                    IconStorage.deleteIcon(context, cachedInfo.iconFileName)
                    IconStorage.deleteIcon(context, cachedInfo.frameworkIconFileName)
                }
                val iconFileName = "${packageName}_library.png"
                val frameworkIconFileName = "${packageName}_framework.png"
                IconStorage.saveIcon(context, iconFileName, appIcon)

                val frameworkIcon = try {
                    val baseApkPath = packageInfo.applicationInfo!!.publicSourceDir
                    val archiveInfo = packageManager.getPackageArchiveInfo(baseApkPath, 0)
                    val cleanAppInfo = archiveInfo?.applicationInfo
                            ?: packageInfo.applicationInfo!!
                    cleanAppInfo.sourceDir = baseApkPath
                    cleanAppInfo.publicSourceDir = baseApkPath

                    val appResources = packageManager.getResourcesForApplication(cleanAppInfo)
                    val iconResId = cleanAppInfo.icon

                    val drawable = if (iconResId != 0) {
                        try {
                            ResourcesCompat.getDrawableForDensity(appResources, iconResId, densityDpi, null)
                        } catch (_: Exception) {
                            packageManager.getApplicationIcon(packageInfo.applicationInfo!!)
                        }
                    } else packageManager.getApplicationIcon(packageInfo.applicationInfo!!)

                    drawable?.toBitmap(appIconSize, appIconSize)
                } catch (_: Exception) {
                    null
                }
                if (frameworkIcon != null) {
                    IconStorage.saveIcon(context, frameworkIconFileName, frameworkIcon)
                    val newCachedInfo = AppIconInfo(
                            packageName = packageName,
                            appName = labelOfLibraryString,
                            lastUpdateTime = lastUpdateTime,
                            iconFileName = iconFileName,
                            frameworkIconFileName = frameworkIconFileName
                    )
                    appIconDao.insert(newCachedInfo)
                }
            }
        }
        SessionTracker.addPackage(packageName)
    }

    private fun prepareXapkFile(packageInfo: PackageInfo, outputFile: File) {
        val baseApkPath = packageInfo.applicationInfo!!.publicSourceDir
        val splitApkPaths = packageInfo.applicationInfo!!.splitPublicSourceDirs?.toList()
                ?: emptyList()
        Log.d("AppLog", "packageName:${packageInfo.packageName} baseApkPath:$baseApkPath splitApkPaths:${splitApkPaths.joinToString()}")
        val allApkFilePaths = listOf(baseApkPath) + splitApkPaths

        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            for (apkPath in allApkFilePaths) {
                val apkFile = File(apkPath)
                val entry = ZipEntry(apkFile.name)
                entry.method = ZipEntry.STORED
                entry.size = apkFile.length()
                entry.compressedSize = apkFile.length()
                entry.crc = calculateCrc(apkFile)
                zos.putNextEntry(entry)
                apkFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun calculateCrc(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private suspend fun testInstalledAppsApks(deviceConfig: DeviceConfig, appIconSize: Int) {
        val context = applicationContext
        val packageManager = context.packageManager
        val densityDpi = applicationContext.resources.displayMetrics.densityDpi
        Log.d("AppLog", "getting all package infos: deviceConfig:$deviceConfig appIconSize:$appIconSize")
        var startTime = System.currentTimeMillis()
        val appsToFocusOn = HashSet<String>()
                .also {
//                    it.add("com.google.android.adservices.api")
//                    it.add("rk.android.app.shortcutmaker")

                    //fail to get icons of these on the Samsung device:

//                    has image file of spr file format:
//                    it.add("com.samsung.advp.imssettings")//ImsSettings.apk
//                    it.add("com.android.nfc")//NfcNci.apk

//                    has bmp files that can't be opened even on Windows OS:
//                    it.add("com.samsung.crane")//Crane.apk
                    //                    it.add("com.sec.android.widgetapp.easymodecontactswidget") //EasymodeContactsWidget81.apk
                }
        val installedPackages =
                packageManager.getInstalledPackagesCompat(PackageManager.GET_META_DATA)
                        .filter { appsToFocusOn.isEmpty() || appsToFocusOn.contains(it.packageName) }

        var endTime = System.currentTimeMillis()
        Log.d("AppLog", "time taken: ${endTime - startTime}. total apps to process: ${installedPackages.size}")
        startTime = endTime
        var apksHandledSoFar = 0
        for ((index, packageInfo) in installedPackages.withIndex()) {
            val packageName = packageInfo.packageName
            val isSystemApp = packageInfo.isSystemApp()

            val baseApkPath = packageInfo.applicationInfo!!.publicSourceDir
            val splitApkPaths = packageInfo.applicationInfo!!.splitPublicSourceDirs?.toList()
                    ?: emptyList()
            val allApkFilePaths = listOf(baseApkPath) + splitApkPaths

            var currentApkInfo: ApkInfo? = null
            var appIcon: Bitmap? = null

            try {
                val filters = allApkFilePaths.map { getZipFilter(context, it, ZIP_FILTER_TYPE) }
                try {
                    val isSeekable = filters.all { it.isSeekable }
                    // Verify each APK individually
                    for ((i, filter) in filters.withIndex()) {
                        val apkFilePath = allApkFilePaths[i]
                        val isBase = apkFilePath == baseApkPath
                        try {
                            val filterToUse = if (isSeekable) NonClosingZipFilter(filter) else getZipFilter(context, apkFilePath, ZIP_FILTER_TYPE)
                            val individualApkInfo = try {
                                ApkInfo.internalGetApkInfo(
                                        deviceConfig, filterToUse,
                                        requestParseResources = false
                                )
                            } finally {
                                if (!isSeekable) filterToUse.closeSilently()
                            }
                            if (individualApkInfo == null) {
                                parsingErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e("AppLog", "can't parse individual apk for \"$packageName\" in: \"$apkFilePath\"")
                            } else {
                                val detectedType = individualApkInfo.apkType
                                val expectedType = if (isBase) ApkInfo.ApkType.BaseOfSplitOrStandalone else ApkInfo.ApkType.Split
                                if (detectedType != expectedType) {
                                    wrongApkTypeErrorsLiveData.inc()
                                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                    Log.e("AppLog", "wrong apk type for \"$packageName\" in: \"$apkFilePath\". Expected $expectedType but got $detectedType isSystemApp?$isSystemApp")
                                }
                                val indApkMeta = individualApkInfo.apkMetaTranslator.apkMeta
                                if (packageInfo.packageName != indApkMeta.packageName) {
                                    wrongPackageNameErrorsLiveData.inc()
                                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                    Log.e("AppLog", "apk package name is different for $apkFilePath : correct one is: \"${packageInfo.packageName}\" vs found: \"${indApkMeta.packageName}\" isSystemApp?$isSystemApp")
                                }
                                if (versionCodeCompat(packageInfo) != indApkMeta.versionCode) {
                                    wrongVersionCodeErrorsLiveData.inc()
                                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                    Log.e("AppLog", "apk version code is different for \"$packageName\" on $apkFilePath : correct one is: ${versionCodeCompat(packageInfo)} vs found: ${indApkMeta.versionCode} isSystemApp?$isSystemApp")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AppLog", "error during individual apk check for $apkFilePath", e)
                        }
                        apkFilesHandledLiveData.inc()
                        ++apksHandledSoFar
                    }

                    currentApkInfo = try {
                        val filtersToUse = if (isSeekable) filters.map { NonClosingZipFilter(it) } else allApkFilePaths.map { getZipFilter(context, it, ZIP_FILTER_TYPE) }
                        try {
                            ApkInfo.getConsolidatedApkInfo(
                                    deviceConfig, filtersToUse,
                                    requestParseResources = VALIDATE_RESOURCES
                            )
                        } finally {
                            if (!isSeekable) filtersToUse.forEach { it.closeSilently() }
                        }
                    } catch (e: Throwable) {
                        Log.e("AppLog", "failed to parse apk for $packageName", e)
                        null
                    }

                    if (currentApkInfo == null) {
                        parsingErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "can't parse apk for \"$packageName\" in: \"$baseApkPath\" isSystemApp?$isSystemApp")
                    } else if (VALIDATE_RESOURCES) {
                        appIcon = ApkIconFetcher.getApkIcon(
                                context, deviceConfig, object : ApkIconFetcher.ZipFilterCreator {
                            override fun generateZipFilter(): AbstractZipFilter =
                                    if (isSeekable) MultiZipFilter(filters.map { NonClosingZipFilter(it) })
                                    else MultiZipFilter(allApkFilePaths.map { getZipFilter(context, it, ZIP_FILTER_TYPE) })
                        }, currentApkInfo, appIconSize)
                        if (packageInfo.applicationInfo!!.icon != 0 && appIcon == null) {
                            failedGettingAppIconErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e("AppLog", "fetching error: can\'t get app icon for \"$packageName\" in: \"$baseApkPath\"")
                        }
                    }
                } finally {
                    filters.forEach { it.closeSilently() }
                }

                if (currentApkInfo != null) {
                    val apkMetaTranslator = currentApkInfo.apkMetaTranslator
                    val apkMeta = apkMetaTranslator.apkMeta
                    if (packageInfo.packageName != apkMeta.packageName) {
                        wrongPackageNameErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "apk package name is different for $baseApkPath : " + "correct one is: \"${packageInfo.packageName}\" vs found: \"${apkMeta.packageName}\" isSystemApp?$isSystemApp")
                    }
                    if (VALIDATE_RESOURCES && packageInfo.versionName != apkMeta.versionName) {
                        wrongVersionNameErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "apk version name is different for \"$packageName\" on $baseApkPath : " + "correct one is: \"${packageInfo.versionName}\" vs found: \"${apkMeta.versionName}\" isSystemApp?$isSystemApp")
                    }
                    if (versionCodeCompat(packageInfo) != apkMeta.versionCode) {
                        wrongVersionCodeErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "apk version code is different for \"$packageName\" on $baseApkPath : correct one is: " + "${
                            versionCodeCompat(packageInfo)
                        } vs found: ${apkMeta.versionCode} isSystemApp?$isSystemApp"
                        )
                    }
                    val labelOfLibrary = apkMeta.label ?: apkMeta.packageName
                    val labelOfLibraryString = labelOfLibrary.toString()
                    if (VALIDATE_RESOURCES) {
                        val expectedAppLabel = packageInfo.applicationInfo!!.loadLabel(packageManager)
                        if (expectedAppLabel != labelOfLibraryString) {
                            wrongLabelErrorsLiveData.inc()
                            if (isSystemApp)
                                systemAppsErrorsCountLiveData.inc()
                            Log.e("AppLog", "label mismatch for \"${packageName}\": correct=\"$expectedAppLabel\" vs found=\"$labelOfLibrary\" apks:${allApkFilePaths.joinToString()}")
                        }

                        val lastUpdateTime = packageInfo.lastUpdateTime
                        val cachedInfo = appIconDao.getByPackageName(packageName)
                        if (cachedInfo == null || cachedInfo.lastUpdateTime != lastUpdateTime) {
                            if (cachedInfo != null) {
                                IconStorage.deleteIcon(context, cachedInfo.iconFileName)
                                IconStorage.deleteIcon(context, cachedInfo.frameworkIconFileName)
                            }
                            if (appIcon != null) {
                                val libIconFileName = "${packageName}_library.png"
                                val fwIconFileName = "${packageName}_framework.png"
                                val savedLib = IconStorage.saveIcon(context, libIconFileName, appIcon)

                                val frameworkIcon = try {
                                    val baseApkPathForIcon = packageInfo.applicationInfo!!.publicSourceDir
                                    val archiveInfo = packageManager.getPackageArchiveInfo(baseApkPathForIcon, 0)
                                    val cleanAppInfo = archiveInfo?.applicationInfo
                                            ?: packageInfo.applicationInfo!!
                                    cleanAppInfo.sourceDir = baseApkPathForIcon
                                    cleanAppInfo.publicSourceDir = baseApkPathForIcon

                                    val appResources = packageManager.getResourcesForApplication(cleanAppInfo)
                                    val iconResId = cleanAppInfo.icon

                                    val drawable = if (iconResId != 0) {
                                        try {
                                            ResourcesCompat.getDrawableForDensity(appResources, iconResId, densityDpi, null)
                                        } catch (_: Exception) {
                                            packageManager.getApplicationIcon(packageInfo.applicationInfo!!)
                                        }
                                    } else packageManager.getApplicationIcon(packageInfo.applicationInfo!!)

                                    drawable?.toBitmap(appIconSize, appIconSize)
                                } catch (_: Exception) {
                                    null
                                }
                                val savedFw = if (frameworkIcon != null) {
                                    IconStorage.saveIcon(context, fwIconFileName, frameworkIcon)
                                } else false

                                if (savedLib && savedFw) {
                                    appIconDao.insert(AppIconInfo(packageName, labelOfLibraryString, lastUpdateTime, libIconFileName, fwIconFileName))
                                }
                            }
                        }
                        SessionTracker.addPackage(packageName)
                    }
                }
            } catch (e: Exception) {
                Log.e("AppLog", "testXapkParsing error for ${packageInfo.packageName}", e)
            }
            appsHandledLiveData.inc()
        }
        endTime = System.currentTimeMillis()
        Log.d("AppLog", "time taken(ms): ${endTime - startTime} . handled ${installedPackages.size} apps, apksCount:$apksHandledSoFar averageTime(ms):${(endTime - startTime).toFloat() / installedPackages.size.toFloat()} per app, ${(endTime - startTime).toFloat() / apksHandledSoFar.toFloat()} per APK")
        Log.d("AppLog", "Final stats: labelErrors=${wrongLabelErrorsLiveData.value}, iconErrors=${failedGettingAppIconErrorsLiveData.value}, parsingErrors=${parsingErrorsLiveData.value}")
    }

    companion object {
        @Suppress("DEPRECATION")
        fun versionCodeCompat(packageInfo: PackageInfo) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()

        enum class ZipFilterType {
            ZipFileFilter,
            ApacheZipFileFilter,
            SeekableInputStreamFilter
        }

        fun getZipFilter(context: Context, apkFilePath: String, zipFilterType: ZipFilterType): AbstractZipFilter {
            return when (zipFilterType) {
                ZipFilterType.ZipFileFilter -> ZipFileFilter(context, ZipFile(apkFilePath))
                ZipFilterType.ApacheZipFileFilter -> {
                    val file = File(apkFilePath)
                    val channel: FileChannel? = FileChannel.open(file.toPath(), StandardOpenOption.READ)
                    val zipFile = org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(channel).get()
                    ApacheZipFileFilter(context, zipFile, underlyingChannel = channel)
                }

                ZipFilterType.SeekableInputStreamFilter -> {
                    val file = File(apkFilePath)
                    val channel = object : SeekableInputStreamByteChannel(file.length()) {
                        override fun getNewInputStream() = FileInputStream(file)
                    }
                    val zipFile = org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(channel).get()
                    ApacheZipFileFilter(context, zipFile, underlyingChannel = channel)
                }
            }
        }
    }
}
