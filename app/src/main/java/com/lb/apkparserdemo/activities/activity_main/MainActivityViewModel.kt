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
import net.dongliu.apk.parser.parser.ResourceTableParser
import net.dongliu.apk.parser.struct.AndroidConstants
import net.dongliu.apk.parser.struct.resource.ResourceTable
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
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
        val allInstalledPackages =
                packageManager.getInstalledPackagesCompat(PackageManager.GET_META_DATA)
        val installedPackages = allInstalledPackages.slice(150 until 160)
        var endTime = System.currentTimeMillis()
        Log.d("AppLog", "time taken: ${endTime - startTime}. total apps to process: ${installedPackages.size}")
        startTime = endTime
        var apksHandledSoFar = 0
        for ((index, packageInfo) in installedPackages.withIndex()) {
            val packageName = packageInfo.packageName
            Log.d("AppLog", "processing index $index: $packageName")
            val isSystemApp = packageInfo.isSystemApp()

            val baseApkPath = packageInfo.applicationInfo!!.publicSourceDir
            val splitApkPaths = packageInfo.applicationInfo!!.splitPublicSourceDirs?.toList() ?: emptyList()
            val allApkFilePaths = listOf(baseApkPath) + splitApkPaths

            // Always build master table if splits exist, to ensure correct labels/icons
            val masterResourceTable: ResourceTable? = if (splitApkPaths.isNotEmpty()) {
                val table = ResourceTable(null)
                for (apkPath in allApkFilePaths) {
                    getZipFilter(apkPath, ZIP_FILTER_TYPE).use { filter ->
                        val resBytes = filter.getByteArrayForEntries(emptySet(), hashSetOf(AndroidConstants.RESOURCE_FILE))?.get(AndroidConstants.RESOURCE_FILE)
                        if (resBytes != null) {
                            try {
                                val parser = ResourceTableParser(ByteBuffer.wrap(resBytes))
                                parser.parse()
                                table.merge(parser.resourceTable)
                            } catch (e: Exception) {
                                Log.e("AppLog", "failed to parse resources of $apkPath: ${e.message}")
                            }
                        }
                    }
                }
                table
            } else null

            val apkInfo = getZipFilter(baseApkPath, ZIP_FILTER_TYPE).use { filter ->
                try {
                    ApkInfo.internalGetApkInfo(locale, filter, requestParseManifestXmlTagForApkType = GET_APK_TYPE, requestParseResources = VALIDATE_RESOURCES, masterResourceTable = masterResourceTable)
                } catch (e: Throwable) {
                    Log.e("AppLog", "failed to parse apk for $packageName: ${e.message}")
                    null
                }
            }

            if (apkInfo == null) {
                parsingErrorsLiveData.inc()
                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                Log.e("AppLog", "can't parse apk for \"$packageName\" in: \"$baseApkPath\" isSystemApp?$isSystemApp")
                continue
            }

            val currentApkInfo = apkInfo!!
            if (VALIDATE_RESOURCES) {
                //check if the library can get app icon, if required
                val appIcon = ApkIconFetcher.getApkIcon(
                        context, locale, object : ApkIconFetcher.ZipFilterCreator {
                    override fun generateZipFilter(): AbstractZipFilter =
                            MultiZipFilter(allApkFilePaths.map { getZipFilter(it, ZIP_FILTER_TYPE) })
                }, currentApkInfo, appIconSize
                )
                if (packageInfo.applicationInfo!!.icon != 0 && appIcon == null) {
                    failedGettingAppIconErrorsLiveData.inc()
                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                    Log.e("AppError", "can\'t get app icon for \"$packageName\" in: \"$baseApkPath\"")
                    // Log all entries in all APKs to see if the requested path exists
                    for (apkPath in allApkFilePaths) {
                        try {
                            ZipFile(apkPath).use { zip ->
                                Log.d("AppLog", "icon fetching: ZIP $apkPath has ${zip.size()} entries")
                                val iconPaths = currentApkInfo.apkMetaTranslator.iconPaths.mapNotNull { it.path }
                                for (p in iconPaths) {
                                    if (zip.getEntry(p) != null) Log.d("AppLog", "icon fetching: found $p in $apkPath")
                                    else {
                                        // Try without leading res/ if it's there
                                        val p2 = if (p.startsWith("res/")) p.substring(4) else p
                                        if (zip.getEntry(p2) != null) Log.d("AppLog", "icon fetching: found $p2 (alt) in $apkPath")
                                    }
                                }
                            }
                        } catch (ignored: Exception) {}
                    }
                }
            }
            when {
                GET_APK_TYPE && currentApkInfo.apkType == ApkInfo.ApkType.UNKNOWN -> {
                    wrongApkTypeErrorsLiveData.inc()
                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                    Log.e("AppLog", "can\'t get apk type for \"$packageName\" in: \"$baseApkPath\" isSystemApp?$isSystemApp")
                }

                GET_APK_TYPE && currentApkInfo.apkType == ApkInfo.ApkType.SPLIT -> {
                    wrongApkTypeErrorsLiveData.inc()
                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                    Log.e("AppLog", "detected as split apk, but in fact a main apk, for \"$packageName\" in: \"$baseApkPath\" isSystemApp?$isSystemApp")
                }

                else -> {}
            }
            val apkMeta = currentApkInfo.apkMetaTranslator.apkMeta
            if (packageInfo.packageName != apkMeta.packageName) {
                wrongPackageNameErrorsLiveData.inc()
                if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                Log.e("AppLog", "apk package name is different for $baseApkPath : " + "correct one is: \"${packageInfo.packageName}\" vs found: \"${apkMeta.packageName}\" isSystemApp?$isSystemApp")
            }
            val apkMetaTranslator = currentApkInfo.apkMetaTranslator
            //compare version name using library vs framework
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
            //compare app label using library vs framework
            val labelOfLibrary = apkMeta.label ?: apkMeta.packageName
            if (VALIDATE_RESOURCES) {
                val potentialLabels = HashSet<CharSequence>()
                packageManager.getPackageArchiveInfo(baseApkPath, 0)?.applicationInfo?.let { appInfo ->
                    if (appInfo.nonLocalizedLabel != null)
                        potentialLabels.add(appInfo.nonLocalizedLabel)
                    potentialLabels.add(appInfo.loadLabel(packageManager))
                }
                packageInfo.applicationInfo!!.let { appInfo ->
                    if (appInfo.nonLocalizedLabel != null)
                        potentialLabels.add(appInfo.nonLocalizedLabel)
                    potentialLabels.add(appInfo.loadLabel(packageManager))
                }
                Log.d("AppLabel", "package: $packageName, library: \"$labelOfLibrary\", framework: ${potentialLabels.joinToString(prefix = "\"", postfix = "\"", separator = "\\")}")
                if (!potentialLabels.contains(labelOfLibrary)) {
                    wrongLabelErrorsLiveData.inc()
                    if (isSystemApp) systemAppsErrorsCountLiveData.inc()
                    val libraryHex = labelOfLibrary?.toString()?.toByteArray(Charsets.UTF_8)?.joinToString("") { "%02x".format(it) }
                    val frameworkHex = potentialLabels.map { it.toString().toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) } }
                    Log.e("AppError", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                    Log.e("AppError", "apk label mismatch for \"${packageName}\": correct=${potentialLabels.joinToString(prefix = "\"", postfix = "\"", separator = "\\")} ($frameworkHex) vs found=\"$labelOfLibrary\" ($libraryHex)")
                    Log.e("AppError", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                }
            }
            Log.d("AppLog", "apk data of $baseApkPath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, $labelOfLibrary, ${apkMetaTranslator.iconPaths}")
            apkFilesHandledLiveData.inc()
            ++apksHandledSoFar
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
        Log.d("AppLog", "Final stats: labelErrors=${wrongLabelErrorsLiveData.value}, iconErrors=${failedGettingAppIconErrorsLiveData.value}, parsingErrors=${parsingErrorsLiveData.value}")
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
