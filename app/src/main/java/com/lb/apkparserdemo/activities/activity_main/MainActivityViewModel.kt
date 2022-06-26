package com.lb.apkparserdemo.activities.activity_main

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lb.apkparserdemo.apk_info.*
import com.lb.apkparserdemo.apk_info.app_icon.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.app_icon.AppInfoUtil
import com.lb.apkparserdemo.apk_info.app_icon.isSystemApp
import com.lb.common_utils.BaseViewModel
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.FileInputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
        val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        var endTime = System.currentTimeMillis()
        Log.d("AppLog", "time taken: ${endTime - startTime}")
        startTime = endTime
        var apksHandledSoFar = 0
        for (packageInfo in installedPackages) {
            val hasSplitApks =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !packageInfo.applicationInfo.splitPublicSourceDirs.isNullOrEmpty()
            val packageName = packageInfo.packageName
            Log.d("AppLog", "checking files of $packageName")
            val isSystemApp = packageInfo.isSystemApp()
            packageInfo.applicationInfo.publicSourceDir.let { apkFilePath ->
                val apkType = ApkInfo.getApkType(packageInfo)
                when {
                    (apkType == ApkInfo.ApkType.STANDALONE || apkType == ApkInfo.ApkType.UNKNOWN) && hasSplitApks -> {
                        Log.e(
                            "AppLog",
                            "detected packageInfo as standalone, but has splits, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                        )
                        frameworkErrorsOfApkTypeLiveData.inc()
                    }
                    apkType == ApkInfo.ApkType.BASE_OF_SPLIT && !hasSplitApks -> {
                        Log.e(
                            "AppLog",
                            "detected packageInfo as base of split, but doesn't have splits, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                        )
                        frameworkErrorsOfApkTypeLiveData.inc()
                    }
                    apkType == ApkInfo.ApkType.SPLIT -> {
                        Log.e(
                            "AppLog",
                            "detected packageInfo as split, but it is not, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                        )
                        frameworkErrorsOfApkTypeLiveData.inc()
                    }
                }
                getZipFilter(apkFilePath, ZIP_FILTER_TYPE).use {
                    var throwable: Throwable? = null
                    val apkInfo = try {
                        ApkInfo.getApkInfo(
                            locale,
                            it,
                            requestParseManifestXmlTagForApkType = GET_APK_TYPE,
                            requestParseResources = VALIDATE_RESOURCES
                        )
                    } catch (e: Throwable) {
                        throwable = e
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
                    if (VALIDATE_RESOURCES) {
                        //check if the library can get app icon, if required
                        val appIcon = ApkIconFetcher.getApkIcon(
                            context, locale, object : ApkIconFetcher.ZipFilterCreator {
                                override fun generateZipFilter(): AbstractZipFilter =
                                    getZipFilter(apkFilePath, ZIP_FILTER_TYPE)
                            }, apkInfo, appIconSize
                        )
                        if (packageInfo.applicationInfo.icon != 0 && appIcon == null) {
                            failedGettingAppIconErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                "AppLog",
                                "can\'t get app icon for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                            )
                        }
                    }
                    when {
                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.UNKNOWN -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                "AppLog",
                                "can\'t get apk type for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                            )
                        }
                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.STANDALONE && hasSplitApks -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                "AppLog",
                                "detected as standalone, but in fact is base of split apks, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                            )
                        }
                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT && !hasSplitApks -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                "AppLog",
                                "detected as base of split apks, but in fact is standalone, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                            )
                        }
                        GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.SPLIT -> {
                            wrongApkTypeErrorsLiveData.inc()
                            if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                            Log.e(
                                "AppLog",
                                "detected as split apk, but in fact a main apk, for \"$packageName\" in: \"$apkFilePath\" isSystemApp?$isSystemApp"
                            )
                        }
                        else -> {
                            val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                            //compare app label using library vs framework
                            val labelOfLibrary = if (!VALIDATE_RESOURCES) "" else apkMeta.label
                                ?: apkMeta.packageName
                            val apkMetaTranslator = apkInfo.apkMetaTranslator
                            val label =
                                if (VALIDATE_RESOURCES) packageInfo.applicationInfo.loadLabel(
                                    packageManager
                                ) else ""
                            if (packageInfo.packageName != apkMeta.packageName) {
                                wrongPackageNameErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                    "AppLog",
                                    "apk package name is different for $apkFilePath : " + "correct one is: \"${packageInfo.packageName}\" vs found: \"${apkMeta.packageName}\" isSystemApp?$isSystemApp"
                                )
                            }
                            //compare version name using library vs framework
                            if (VALIDATE_RESOURCES && packageInfo.versionName != apkMeta.versionName) {
                                wrongVersionNameErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                    "AppLog",
                                    "apk version name is different for \"$packageName\" on $apkFilePath : " + "correct one is: \"${packageInfo.versionName}\" vs found: \"${apkMeta.versionName}\" isSystemApp?$isSystemApp"
                                )
                            }
                            if (versionCodeCompat(packageInfo) != apkMeta.versionCode) {
                                wrongVersionCodeErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                    "AppLog",
                                    "apk version code is different for \"$packageName\" on $apkFilePath : " + "correct one is: " + "${
                                        versionCodeCompat(packageInfo)
                                    } vs found: ${apkMeta.versionCode} isSystemApp?$isSystemApp"
                                )
                            }
                            //compare app label using library vs framework
                            if (VALIDATE_RESOURCES && label != labelOfLibrary) {
                                wrongLabelErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                    "AppLog",
                                    "apk label is different for \"${packageName}\" on $apkFilePath : correct one is: \"$label\" vs found: \"$labelOfLibrary\" isSystemApp?$isSystemApp"
                                )
                            }
                            Log.d(
                                "AppLog",
                                "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, $labelOfLibrary, ${apkMetaTranslator.iconPaths}"
                            )
                        }
                    }
                }
                apkFilesHandledLiveData.inc()
                ++apksHandledSoFar
            }
            //done with base APK. Now parsing the split APK files of the app, if possible:
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                packageInfo.applicationInfo.splitPublicSourceDirs?.forEach { apkFilePath ->
                    getZipFilter(apkFilePath, ZIP_FILTER_TYPE).use {
                        var throwable: Throwable? = null
                        val apkInfo = try {
                            ApkInfo.getApkInfo(
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
                            GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.STANDALONE -> {
                                wrongApkTypeErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                    "AppLog",
                                    "detected as standalone, but in fact is split apk: $apkFilePath isSystemApp?$isSystemApp"
                                )
                            }
                            GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT -> {
                                wrongApkTypeErrorsLiveData.inc()
                                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                                Log.e(
                                    "AppLog",
                                    "detected as base of split apks, but in fact is split apk: $apkFilePath isSystemApp?$isSystemApp"
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
                                    "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, ${apkMeta.name}, ${apkMetaTranslator.iconPaths}"
                                )
                            }
                        }
                    }
                    apkFilesHandledLiveData.inc()
                    ++apksHandledSoFar
                }
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
        fun versionCodeCompat(packageInfo: PackageInfo) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()

        enum class ZipFilterType {
            //this is the order from fastest to slowest, according to my tests:
            ZipFileFilter, ApacheZipArchiveInputStreamFilter, ZipInputStreamFilter, ApacheZipFileFilter
        }

        fun getZipFilter(apkFilePath: String, zipFilterType: ZipFilterType): AbstractZipFilter {
            return when (zipFilterType) {
                ZipFilterType.ZipFileFilter -> ZipFileFilter(ZipFile(apkFilePath))
                ZipFilterType.ApacheZipFileFilter -> ApacheZipFileFilter(
                    org.apache.commons.compress.archivers.zip.ZipFile(
                        apkFilePath
                    )
                )
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
