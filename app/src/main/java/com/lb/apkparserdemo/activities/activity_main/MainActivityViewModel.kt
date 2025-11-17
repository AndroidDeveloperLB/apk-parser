package com.lb.apkparserdemo.activities.activity_main

import android.app.Application
import android.content.pm.*
import android.os.Build
import android.util.Log
import androidx.annotation.*
import androidx.lifecycle.*
import com.lb.apkparserdemo.apk_info.*
import com.lb.apkparserdemo.apk_info.app_icon.*
import com.lb.common_utils.BaseViewModel
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.*

private const val VALIDATE_RESOURCES = true
private const val GET_APK_TYPE = true
private val ZIP_FILTER_TYPE: MainActivityViewModel.Companion.ZipFilterType =
        MainActivityViewModel.Companion.ZipFilterType.ZipFileFilter

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
    val isDoneLiveData = MutableLiveData<Boolean>(false)

    private var fetchAppInfoJob: Job? = null
    private val fetchAppInfoDispatcher: CoroutineDispatcher =
            Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    @Suppress("ConstantConditionIf")
    @UiThread
    fun init() {
        if (fetchAppInfoJob != null) return
        fetchAppInfoJob = viewModelScope.launch {
            runInterruptible(fetchAppInfoDispatcher) {
                performTests()
            }
        }
    }

    @WorkerThread
    private fun performTests() {
        val locale = Locale.getDefault()
        val context = applicationContext
        val appIconSize = AppInfoUtil.getAppIconSize(context)
        val packageManager = context.packageManager
        Log.d("AppLog", "getting all package infos:")
        var startTime = System.currentTimeMillis()
        val installedPackages =
                packageManager.getInstalledPackagesCompat(PackageManager.GET_META_DATA)
//                        .filter{it.packageName=="com.google.android.webview"}
        var endTime = System.currentTimeMillis()
        Log.d("AppLog", "time taken: ${endTime - startTime}")
        startTime = endTime
        var apksHandledSoFar = 0
        for (packageInfo in installedPackages) {
            val hasSplitApks = !packageInfo.applicationInfo!!.splitPublicSourceDirs.isNullOrEmpty()
            val packageName = packageInfo.packageName
            Log.d("AppLog", "checking files of $packageName")
            val isSystemApp = packageInfo.isSystemApp()
            //first check the main APK of each app
            packageInfo.applicationInfo!!.publicSourceDir.let { apkFilePath ->
                getZipFilter(apkFilePath, ZIP_FILTER_TYPE).use {
                    var throwable: Throwable? = null
                    val apkInfo = try {
                        ApkInfo.internalGetApkInfo(locale, it, requestParseManifestXmlTagForApkType = GET_APK_TYPE, requestParseResources = VALIDATE_RESOURCES)
                    } catch (e: Throwable) {
                        throwable = e
                        e.printStackTrace()
                        null
                    }
                    if (apkInfo == null) {
                        parsingErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        if (throwable != null)
                            Log.e("AppLog", "can't parse apk for \"$packageName\" in: \"$apkFilePath\" - exception:$throwable isSystemApp?$isSystemApp")
                        else
                            Log.e("AppLog", "can't parse apk for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp")
                        return@use
                    }
                    if (VALIDATE_RESOURCES) {
                        //check if the library can get app icon, if required
                        val appIcon = ApkIconFetcher.getApkIcon(
                                context, locale, object : ApkIconFetcher.ZipFilterCreator {
                            override fun generateZipFilter(): AbstractZipFilter =
                                    getZipFilter(apkFilePath, ZIP_FILTER_TYPE)
                        }, apkInfo, appIconSize
                        )
                        if (packageInfo.applicationInfo!!.icon != 0 && appIcon == null) {
                            failedGettingAppIconErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e("AppLog", "can\'t get app icon for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp")
                        }
                    }
                    when {
                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.UNKNOWN -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e("AppLog", "can\'t get apk type for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp")
                        }

                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.SPLIT -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e("AppLog", "detected as split apk, but in fact a main apk, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp")
                        }

                        else -> {}
                    }
                    val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                    if (packageInfo.packageName != apkMeta.packageName) {
                        wrongPackageNameErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "apk package name is different for $apkFilePath : " + "correct one is: \"${packageInfo.packageName}\" vs found: \"${apkMeta.packageName}\" isSystemApp?$isSystemApp")
                    }
                    val apkMetaTranslator = apkInfo.apkMetaTranslator
                    //compare version name using library vs framework
                    if (VALIDATE_RESOURCES && packageInfo.versionName != apkMeta.versionName) {
                        wrongVersionNameErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "apk version name is different for \"$packageName\" on $apkFilePath : " + "correct one is: \"${packageInfo.versionName}\" vs found: \"${apkMeta.versionName}\" isSystemApp?$isSystemApp")
                    }
                    if (versionCodeCompat(packageInfo) != apkMeta.versionCode) {
                        wrongVersionCodeErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        Log.e("AppLog", "apk version code is different for \"$packageName\" on $apkFilePath : correct one is: " + "${
                            versionCodeCompat(packageInfo)
                        } vs found: ${apkMeta.versionCode} isSystemApp?$isSystemApp"
                        )
                    }
                    //compare app label using library vs framework
                    val labelOfLibrary = apkMeta.label ?: apkMeta.packageName
                    if (VALIDATE_RESOURCES) {
                        val potentialLabels = HashSet<CharSequence>()
                        packageManager.getPackageArchiveInfo(apkFilePath, 0)!!.applicationInfo!!.let { appInfo ->
                            if (appInfo.nonLocalizedLabel != null)
                                potentialLabels.add(appInfo.nonLocalizedLabel)
                            potentialLabels.add(appInfo.loadLabel(packageManager))
                        }
                        packageInfo.applicationInfo!!.let { appInfo ->
                            if (appInfo.nonLocalizedLabel != null)
                                potentialLabels.add(appInfo.nonLocalizedLabel)
                            potentialLabels.add(appInfo.loadLabel(packageManager))
                        }
                        if (!potentialLabels.contains(labelOfLibrary)) {
                            wrongLabelErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e("AppLog", "apk label is different for \"${packageName}\" on $apkFilePath : correct one is : ${potentialLabels.joinToString(prefix = "\"", postfix = "\"", separator = "\\")} vs found: \"$labelOfLibrary\" isSystemApp?$isSystemApp")
                        }
                    }
                    Log.d("AppLog", "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, $labelOfLibrary, ${apkMetaTranslator.iconPaths}")
                }
                apkFilesHandledLiveData.inc()
                ++apksHandledSoFar
            }
            //done with base APK. Now parsing the split APK files of the app, if possible:
            packageInfo.applicationInfo!!.splitPublicSourceDirs?.forEach { apkFilePath ->
                getZipFilter(apkFilePath, ZIP_FILTER_TYPE).use {
                    var throwable: Throwable? = null
                    val apkInfo = try {
                        ApkInfo.internalGetApkInfo(
                                locale,
                                it,
                                requestParseManifestXmlTagForApkType = GET_APK_TYPE,
                                requestParseResources = VALIDATE_RESOURCES
                        )
                    } catch (e: Throwable) {
                        throwable = e
                        parsingErrorsLiveData.inc()
                        e.printStackTrace()
                        null
                    }
                    if (apkInfo == null) {
                        parsingErrorsLiveData.inc()
                        if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                        if (throwable != null) Log.e(
                                "AppLog",
                                "can't parse apk for \"$packageName\" in: \"$apkFilePath\" - exception:$throwable isSystemApp?$isSystemApp"
                        )
                        else Log.e(
                                "AppLog",
                                "can't parse apk for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                        )
                        return@use
                    }
                    when {
                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.UNKNOWN -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                    "AppLog",
                                    "can\'t get apk type: $apkFilePath isSystemApp?$isSystemApp"
                            )
                        }

                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT_OR_STANDALONE -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                    "AppLog",
                                    "detected as base/standalone apk, but in fact is split apk: $apkFilePath isSystemApp?$isSystemApp"
                            )
                        }

                        else -> {
                            val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                            val apkMetaTranslator = apkInfo.apkMetaTranslator
                            if (packageInfo.packageName != apkMeta.packageName) {
                                wrongPackageNameErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                        "AppLog",
                                        "apk package name is different for $apkFilePath : correct one is: $packageName vs found: ${apkMeta.packageName} isSystemApp?$isSystemApp"
                                )
                            }
                            if (versionCodeCompat(packageInfo) != apkMeta.versionCode) {
                                wrongVersionCodeErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                        "AppLog",
                                        "apk version code is different for $apkFilePath : correct one is: ${
                                            versionCodeCompat(packageInfo)
                                        } vs found: ${apkMeta.versionCode} isSystemApp?$isSystemApp"
                                )
                            }
                            Log.d(
                                    "AppLog",
                                    "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, ${apkMeta.label}, ${apkMetaTranslator.iconPaths}"
                            )
                        }
                    }
                }
                apkFilesHandledLiveData.inc()
                ++apksHandledSoFar
            }
            appsHandledLiveData.inc()
        }
        endTime = System.currentTimeMillis()
        Log.d(
                "AppLog",
                "time taken(ms): ${endTime - startTime} . handled ${installedPackages.size} apps, apksCount:$apksHandledSoFar"
        )
        Log.d(
                "AppLog",
                "averageTime(ms):${(endTime - startTime).toFloat() / installedPackages.size.toFloat()} per app, ${(endTime - startTime).toFloat() / apksHandledSoFar.toFloat()} per APK"
        )
        Log.e("AppLog", "done")
        isDoneLiveData.postValue(true)
    }

    companion object {
        @Suppress("DEPRECATION")
        fun versionCodeCompat(packageInfo: PackageInfo) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()

        enum class ZipFilterType {
            //this is the order from fastest to slowest, according to my tests:
            ZipFileFilter, ApacheZipArchiveInputStreamFilter, ZipInputStreamFilter, ApacheZipFileFilter
        }

        fun getZipFilter(apkFilePath: String, zipFilterType: ZipFilterType): AbstractZipFilter {
            return when (zipFilterType) {
                ZipFilterType.ZipFileFilter -> ZipFileFilter(ZipFile(apkFilePath))
                ZipFilterType.ApacheZipFileFilter -> {
                    ApacheZipFileFilter(org.apache.commons.compress.archivers.zip.ZipFile.Builder().setPath(apkFilePath).get())
                }

                ZipFilterType.ApacheZipArchiveInputStreamFilter -> ApacheZipArchiveInputStreamFilter(
                        ZipArchiveInputStream(
                                FileInputStream(
                                        apkFilePath
                                ), "UTF8", true, true
                        )
                )

                ZipFilterType.ZipInputStreamFilter -> ZipInputStreamFilter(
                        ZipInputStream(
                                FileInputStream(apkFilePath)
                        )
                )
            }
        }
    }
}
