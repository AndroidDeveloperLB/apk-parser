package com.lb.apkparserdemo.activities.activity_main

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewModelScope
import com.lb.apkparserdemo.R
import com.lb.apkparserdemo.apk_info.AbstractZipFilter
import com.lb.apkparserdemo.apk_info.ApacheZipFileFilter
import com.lb.apkparserdemo.apk_info.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.ApkInfo
import com.lb.apkparserdemo.apk_info.ApkParsingResult
import com.lb.apkparserdemo.apk_info.MemoryUtils
import com.lb.apkparserdemo.apk_info.MultiZipFilter
import com.lb.apkparserdemo.apk_info.NonClosingZipFilter
import com.lb.apkparserdemo.apk_info.ZipFileFilter
import com.lb.apkparserdemo.apk_info.zip.SeekableInputStreamByteChannel
import com.lb.apkparserdemo.db.AppDatabase
import com.lb.apkparserdemo.db.AppIconInfo
import com.lb.apkparserdemo.testing.XapkTestHandler2
import com.lb.apkparserdemo.testing.XapkTestHandlerExperimental
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

        testXapkParsing(deviceConfig, appIconSize)
//        testInstalledAppsApks(deviceConfig, appIconSize)
        Log.e("AppLog", "done")
        isDoneLiveData.postValue(true)
    }

    @WorkerThread
    private suspend fun testXapkParsing(deviceConfig: DeviceConfig, appIconSize: Int) {
        val context = applicationContext
        val packageManager = context.packageManager
        val appsToFocusOn = HashSet<String>()
                .also {
//                    Samsung issue: has image file of spr file format:
//                    it.add("com.samsung.advp.imssettings")//ImsSettings.apk
//                    it.add("com.android.nfc")//NfcNci.apk

//                    Samsung issue: has bmp files that can't be opened even on Windows OS:
//                    it.add("com.samsung.crane")//Crane.apk
                    //                    it.add("com.sec.android.widgetapp.easymodecontactswidget") //EasymodeContactsWidget81.apk
                }
        val installedPackages = packageManager.getInstalledPackagesCompat(PackageManager.GET_META_DATA)
//                .filter { appsToFocusOn.isEmpty() || appsToFocusOn.contains(it.packageName) }
//                .filter {
//                    if (!it.applicationInfo!!.isSystemApp())
//                        return@filter false
//                    val size = it.applicationInfo?.splitPublicSourceDirs?.size ?: 0
//                    size > 0
//                }
//                .subList(0, 3)
        val useMemCache: Boolean = false
        val preferApacheApi: Boolean = true
        val useUncompressedZipFiles: Boolean = false
        File(context.cacheDir, "test.xapk").let { xapkFile ->
            copyRawToFile(context, R.raw.test, xapkFile)
//            FileInputStream(xapkFile).use {
//                Log.d("AppLog", "isProblematic?${MemoryUtils.hasProblematicZipEntries(it)}")
//            }
            val apkParsingResult =
//                    XapkTestHandlerExperimental(context).runTestMemory(xapkFile,deviceConfig, appIconSize)
            XapkTestHandlerExperimental(context).runTestOnFile(xapkFile,deviceConfig, appIconSize)
//                            com.lb.apkparserdemo.testing.XapkTestHandler(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//                XapkTestHandler2(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//                com.lb.apkparserdemo.testing.XapkTestHandler3(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//                com.lb.apkparserdemo.testing.XapkTestHandler4(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)
//                com.lb.apkparserdemo.testing.XapkTestHandler5(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)
//                    com.lb.apkparserdemo.testing.XapkTestHandler6(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandler7(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework1(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework2(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework3(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework4(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework5(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework6(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)
//                com.lb.apkparserdemo.testing.XapkTestHandlerFramework7(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)

            Log.d("AppLog", "result:$apkParsingResult")
            return
        }

        var totalParsingTime = 0L
        var apksHandledSoFar = 0
        Log.d("AppLog", "testing on ${installedPackages.size} apps")
        for ((index, packageInfo) in installedPackages.withIndex()) {
            val xapkFile = File(context.cacheDir, "${packageInfo.packageName}.xapk")
//            if(packageInfo.packageName =="com.google.android.googlequicksearchbox")
//                xapkFile.delete()
            prepareXapkFile(packageInfo, xapkFile, useUncompressedZipFiles)
            Log.d("AppLog", "${index}/${installedPackages.size}")
        }
        for (packageInfo in installedPackages) {
            val xapkFile = File(context.cacheDir, "${packageInfo.packageName}.xapk")
//            val useUncompressedZipFiles = true
//            prepareXapkFile(packageInfo, xapkFile, useUncompressedZipFiles)
            Log.d("AppLog", "packageName:${packageInfo.packageName} baseApkPath:$${packageInfo.applicationInfo!!.publicSourceDir} splitApkPaths:${packageInfo.applicationInfo!!.splitPublicSourceDirs?.joinToString()}")

            val stepStartTime = System.currentTimeMillis()
            val result: ApkParsingResult? = try {
                // XAPK test
//uncompressed:
//24:time taken(ms): 12933 . handled 99 apps, apksCount:99 averageTime(ms):130.63637 per app, 130.63637 per APK
//34 apache:time taken(ms): 45645 . handled 224 apps, apksCount:245 averageTime(ms):203.77232 per app, 186.30612 per APK
//34 no apache time taken(ms): 169247 . handled 224 apps, apksCount:245 averageTime(ms):755.56696 per app, 690.8041 per APK
//compressed:
//24:time taken(ms): 24649 . handled 99 apps, apksCount:99 averageTime(ms):248.9798 per app, 248.9798 per APK
//34 apache: time taken(ms): 124902 . handled 224 apps, apksCount:245 averageTime(ms):557.5982 per app, 509.80408 per APK
//34 no apache
//                com.lb.apkparserdemo.testing.XapkTestHandler(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24:time taken(ms): 13343 . handled 99 apps, apksCount:99 averageTime(ms):134.77777 per app, 134.77777 per APK
//34 apache:time taken(ms): 46006 . handled 224 apps, apksCount:245 averageTime(ms):205.38393 per app, 187.77959 per APK
//34 no apache
//compressed:
//24:time taken(ms): 24846 . handled 99 apps, apksCount:99 averageTime(ms):250.9697 per app, 250.9697 per APK
//34 apache: time taken(ms): 129285 . handled 224 apps, apksCount:245 averageTime(ms):577.16516 per app, 527.69385 per APK
//34 no apache
//                XapkTestHandler2(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24: time taken(ms): 13175 . handled 99 apps, apksCount:99 averageTime(ms):133.08081 per app, 133.08081 per APK
//34 apache:time taken(ms): 60926 . handled 224 apps, apksCount:245 averageTime(ms):271.99106 per app, 248.67755 per APK
//34 no apache
//compressed:
//24:time taken(ms): 25010 . handled 99 apps, apksCount:99 averageTime(ms):252.62627 per app, 252.62627 per APK
//34 apache:time taken(ms): 129239 . handled 224 apps, apksCount:245 averageTime(ms):576.95984 per app, 527.5061 per APK
//34 no apache
//                com.lb.apkparserdemo.testing.XapkTestHandler3(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)


//uncompressed:
//24:time taken(ms): 12181 . handled 99 apps, apksCount:99 averageTime(ms):123.040405 per app, 123.040405 per APK
//34 apache:time taken(ms): 45529 . handled 224 apps, apksCount:245 averageTime(ms):203.25447 per app, 185.83266 per APK
//34 no apache
//compressed:
//24:time taken(ms): 26052 . handled 99 apps, apksCount:99 averageTime(ms):263.15152 per app, 263.15152 per APK
//34 apache:time taken(ms): 129933 . handled 224 apps, apksCount:245 averageTime(ms):580.05804 per app, 530.33875 per APK
//34 no apache
//                com.lb.apkparserdemo.testing.XapkTestHandler4(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)

//uncompressed:
//24:time taken(ms): 12499 . handled 99 apps, apksCount:99 averageTime(ms):126.252525 per app, 126.252525 per APK
//34 apache: time taken(ms): 45654 . handled 224 apps, apksCount:245 averageTime(ms):203.8125 per app, 186.34285 per APK
//compressed:
//24:time taken(ms): 27858 . handled 99 apps, apksCount:99 averageTime(ms):281.39395 per app, 281.39395 per APK
//34 apache:time taken(ms): 130251 . handled 224 apps, apksCount:245 averageTime(ms):581.47766 per app, 531.6367 per APK
//34 no apache
//                com.lb.apkparserdemo.testing.XapkTestHandler5(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)

//uncompressed:
//24:time taken(ms): 14329 . handled 99 apps, apksCount:99 averageTime(ms):144.73738 per app, 144.73738 per APK
//34 apache:time taken(ms): 43104 . handled 224 apps, apksCount:245 averageTime(ms):192.42857 per app, 175.9347 per APK
//34 no apache time taken(ms): 191251 . handled 224 apps, apksCount:245 averageTime(ms):853.79913 per app, 780.61633 per APK
//pixel6 apache:
//pixel6 no apache:
//compressed:
//24:time taken(ms): 29066 . handled 99 apps, apksCount:99 averageTime(ms):293.59595 per app, 293.59595 per APK
//34 apache time taken(ms): 128561 . handled 224 apps, apksCount:245 averageTime(ms):573.93304 per app, 524.7388 per APK
//34 no apache time taken(ms): 293738 . handled 224 apps, apksCount:245 averageTime(ms):1311.3303 per app, 1198.9307 per APK
//pixel6 apache: time taken(ms): 735058 . handled 416 apps, apksCount:845 averageTime(ms):1766.9663 per app, 869.8911 per APK
//pixel6 no apache:
                com.lb.apkparserdemo.testing.XapkTestHandler6(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24: time taken(ms): 14498 . handled 99 apps, apksCount:99 averageTime(ms):146.44444 per app, 146.44444 per APK
//34 apache:time taken(ms): 42319 . handled 224 apps, apksCount:245 averageTime(ms):188.9241 per app, 172.7306 per APK
//34 no apache
//compressed:
//24:time taken(ms): 28479 . handled 99 apps, apksCount:99 averageTime(ms):287.66666 per app, 287.66666 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandler7(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)

//uncompressed:
//24:time taken(ms): 12626 . handled 99 apps, apksCount:99 averageTime(ms):127.535355 per app, 127.535355 per APK
//34 apache:time taken(ms): 59473 . handled 224 apps, apksCount:245 averageTime(ms):265.50446 per app, 242.74693 per APK
//34 no apache
//compressed:
//24:time taken(ms): 25056 . handled 99 apps, apksCount:99 averageTime(ms):253.09091 per app, 253.09091 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework1(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24:time taken(ms): 12484 . handled 99 apps, apksCount:99 averageTime(ms):126.10101 per app, 126.10101 per APK
//34 apache:time taken(ms): 59869 . handled 224 apps, apksCount:245 averageTime(ms):267.2723 per app, 244.36327 per APK
//34 no apache
//compressed:
//24:time taken(ms): 25928 . handled 99 apps, apksCount:99 averageTime(ms):261.899 per app, 261.899 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework2(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24:time taken(ms): 12225 . handled 99 apps, apksCount:99 averageTime(ms):123.48485 per app, 123.48485 per APK
//34 apache: time taken(ms): 59963 . handled 224 apps, apksCount:245 averageTime(ms):267.69196 per app, 244.74693 per APK
//34 no apache
//compressed:
//24:time taken(ms): 25769 . handled 99 apps, apksCount:99 averageTime(ms):260.29294 per app, 260.29294 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework3(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24:time taken(ms): 12244 . handled 99 apps, apksCount:99 averageTime(ms):123.676765 per app, 123.676765 per APK
//34 apache:time taken(ms): 59072 . handled 224 apps, apksCount:245 averageTime(ms):263.7143 per app, 241.1102 per APK
//34 no apache
//compressed:
//24:time taken(ms): 25845 . handled 99 apps, apksCount:99 averageTime(ms):261.0606 per app, 261.0606 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework4(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)

//uncompressed:
//24:time taken(ms): 12322 . handled 99 apps, apksCount:99 averageTime(ms):124.464645 per app, 124.464645 per APK
//34 apache:time taken(ms): 45849 . handled 224 apps, apksCount:245 averageTime(ms):204.68303 per app, 187.13878 per APK
//34 no apache
//compressed:
//24:time taken(ms): 26138 . handled 99 apps, apksCount:99 averageTime(ms):264.0202 per app, 264.0202 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework5(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)

//uncompressed:
//24:time taken(ms): 13074 . handled 99 apps, apksCount:99 averageTime(ms):132.06061 per app, 132.06061 per APK
//34 apache:time taken(ms): 56009 . handled 224 apps, apksCount:245 averageTime(ms):250.04018 per app, 228.60817 per APK
//34 no apache time taken(ms): 173638 . handled 224 apps, apksCount:245 averageTime(ms):775.1696 per app, 708.7265 per APK
//compressed:
//24:time taken(ms): 25269 . handled 99 apps, apksCount:99 averageTime(ms):255.24243 per app, 255.24243 per APK
//34 apache:
//34 no apache
//            com.lb.apkparserdemo.testing.XapkTestHandlerFramework6(context).runTest(xapkFile, deviceConfig, appIconSize, preferApacheApi)

//uncompressed:
//24:time taken(ms): 13284 . handled 99 apps, apksCount:99 averageTime(ms):134.18182 per app, 134.18182 per APK
//34 apache:time taken(ms): 42958 . handled 224 apps, apksCount:245 averageTime(ms):191.77678 per app, 175.33878 per APK
//34 no apache time taken(ms): 172875 . handled 224 apps, apksCount:245 averageTime(ms):771.76337 per app, 705.61224 per APK
//compressed:
//24:time taken(ms): 25845 . handled 99 apps, apksCount:99 averageTime(ms):261.0606 per app, 261.0606 per APK
//34 apache:
//34 no apache
//                com.lb.apkparserdemo.testing.XapkTestHandlerFramework7(context).runTest(xapkFile, deviceConfig, appIconSize, useMemCache, preferApacheApi)
            } catch (e: Throwable) {
                Log.e("AppLog", "testXapkParsing error for ${packageInfo.packageName}", e)
                null
            }
            val singleTestTime = System.currentTimeMillis() - stepStartTime
//            Log.d("AppLog", "testXapkParsing time taken(ms): $singleTestTime result:$result")
            totalParsingTime += singleTestTime
            handleParsingResult(packageInfo, result, appIconSize)
            apksHandledSoFar += 1 + (packageInfo.applicationInfo?.splitPublicSourceDirs?.size ?: 0)
            apkFilesHandledLiveData.postValue(apksHandledSoFar)
            appsHandledLiveData.inc()
//            break
        }
//        xapkFile.delete()
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

    private fun prepareXapkFile(packageInfo: PackageInfo, outputFile: File, useUncompressedZipFiles: Boolean) {
        if (outputFile.exists())
            return
        val baseApkPath = packageInfo.applicationInfo!!.publicSourceDir
        val splitApkPaths = packageInfo.applicationInfo!!.splitPublicSourceDirs?.toList()
                ?: emptyList()
        val allApkFilePaths = listOf(baseApkPath) + splitApkPaths

        if (useUncompressedZipFiles)
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
        else
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                zos.setMethod(ZipOutputStream.DEFLATED)
                for (apkPath in allApkFilePaths) {
                    val apkFile = File(apkPath)
                    val entry = ZipEntry(apkFile.name)
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
