package com.lb.apkparserdemo

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lb.apkparserdemo.apk_info.*
import com.lb.apkparserdemo.apk_info.app_icon.ApkIconFetcher
import com.lb.apkparserdemo.apk_info.app_icon.AppInfoUtil
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.FileInputStream
import java.util.*
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

private const val VALIDATE_RESOURCES = true
private const val GET_APK_TYPE = false
private val ZIP_FILTER_TYPE: MainActivity.Companion.ZipFilterType =
    MainActivity.Companion.ZipFilterType.ZipFileFilter

inline fun <reified T : Any> Context.getSystemService(): T =
    ContextCompat.getSystemService(this, T::class.java)!!

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null)
            performTest()
    }

    @Suppress("ConstantConditionIf")
    private fun performTest() {
        val locale = Locale.getDefault()
        val appIconSize = AppInfoUtil.getAppIconSize(this)
        thread {
            Log.d("AppLog", "getting all package infos:")
            var startTime = System.currentTimeMillis()
            val installedPackages =
                packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            var endTime = System.currentTimeMillis()
            Log.d("AppLog", "time taken: ${endTime - startTime}")
            startTime = endTime
            var apksHandledSoFar = 0
            for (packageInfo in installedPackages) {
                val hasSplitApks =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !packageInfo.applicationInfo.splitPublicSourceDirs.isNullOrEmpty()
                val packageName = packageInfo.packageName
                Log.d("AppLog", "checking files of $packageName")
                packageInfo.applicationInfo.publicSourceDir.let { apkFilePath ->
                    val apkType = ApkInfo.getApkType(packageInfo)
                    when {
                        apkType == ApkInfo.ApkType.STANDALONE && hasSplitApks -> Log.e(
                            "AppLog",
                            "detected packageInfo as standalone, but has splits, for \"$packageName\" in: \"$apkFilePath\" "
                        )
                        apkType == ApkInfo.ApkType.BASE_OF_SPLIT && !hasSplitApks -> Log.e(
                            "AppLog",
                            "detected packageInfo as base of split, but doesn't have splits, for \"$packageName\" in: \"$apkFilePath\" "
                        )
                        apkType == ApkInfo.ApkType.SPLIT -> Log.e(
                            "AppLog",
                            "detected packageInfo as split, but it is not, for \"$packageName\" in: \"$apkFilePath\" "
                        )
                    }
                    var baseApkInfo: ApkInfo? = null
                    getZipFilter(apkFilePath, ZIP_FILTER_TYPE).use {
                        val apkInfo = try {
                            ApkInfo.getApkInfo(
                                locale,
                                it,
                                requestParseManifestXmlTagForApkType = GET_APK_TYPE,
                                requestParseResources = VALIDATE_RESOURCES
                            )
                        } catch (e: Exception) {
                            Log.e(
                                "AppLog",
                                "can't parse apk for \"$packageName\" in: \"$apkFilePath\" - exception:$e"
                            )
                            e.printStackTrace()
                            return@use
                        }
                        baseApkInfo = apkInfo
                        if (apkInfo != null && VALIDATE_RESOURCES) {
                            val appIcon = ApkIconFetcher.getApkIcon(
                                this,
                                locale,
                                object : ApkIconFetcher.ZipFilterCreator {
                                    override fun generateZipFilter(): AbstractZipFilter =
                                        getZipFilter(apkFilePath, ZIP_FILTER_TYPE)
                                },
                                apkInfo,
                                appIconSize
                            )
                            if (packageInfo.applicationInfo.icon != 0 && appIcon == null)
                                Log.e(
                                    "AppLog",
                                    "can\'t get app icon for \"$packageName\" in: \"$apkFilePath\" "
                                )
                        }
                        when {
                            apkInfo == null -> Log.e(
                                "AppLog",
                                "can't parse for \"$packageName\" in: \"$apkFilePath\""
                            )
                            GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.UNKNOWN -> Log.e(
                                "AppLog",
                                "can\'t get apk type for \"$packageName\" in: \"$apkFilePath\"  "
                            )
                            GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.STANDALONE && hasSplitApks -> Log.e(
                                "AppLog",
                                "detected as standalone, but in fact is base of split apks, for \"$packageName\" in: \"$apkFilePath\" "
                            )
                            GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT && !hasSplitApks -> Log.e(
                                "AppLog",
                                "detected as base of split apks, but in fact is standalone, for \"$packageName\" in: \"$apkFilePath\" "
                            )
                            GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.SPLIT -> Log.e(
                                "AppLog",
                                "detected as split apk, but in fact a main apk, for \"$packageName\" in: \"$apkFilePath\" "
                            )
                            else -> {
                                val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                                val labelOfLibrary = if (!VALIDATE_RESOURCES) "" else apkMeta.label
                                    ?: apkMeta.packageName
                                val apkMetaTranslator = apkInfo.apkMetaTranslator
                                val label =
                                    if (VALIDATE_RESOURCES) packageInfo.applicationInfo.loadLabel(
                                        packageManager
                                    ) else ""
                                when {
                                    packageInfo.packageName != apkMeta.packageName -> Log.e(
                                        "AppLog",
                                        "apk package name is different for $apkFilePath : correct one is: \"${packageInfo.packageName}\" vs found: \"${apkMeta.packageName}\""
                                    )
                                    VALIDATE_RESOURCES && packageInfo.versionName != apkMeta.versionName -> Log.e(
                                        "AppLog",
                                        "apk version name is different for \"$packageName\" on $apkFilePath : correct one is: \"${packageInfo.versionName}\" vs found: \"${apkMeta.versionName}\""
                                    )
                                    versionCodeCompat(packageInfo) != apkMeta.versionCode -> Log.e(
                                        "AppLog",
                                        "apk version code is different for \"$packageName\" on $apkFilePath : correct one is: " +
                                                "${versionCodeCompat(packageInfo)} vs found: ${apkMeta.versionCode}"
                                    )
                                    VALIDATE_RESOURCES && label != labelOfLibrary -> Log.e(
                                        "AppLog",
                                        "apk label is different for \"${packageName}\" on $apkFilePath : correct one is: \"$label\" vs found: \"$labelOfLibrary\""
                                    )
                                    else -> {
                                        Log.d(
                                            "AppLog",
                                            "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, $labelOfLibrary, ${apkMetaTranslator.iconPaths}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ++apksHandledSoFar
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    packageInfo.applicationInfo.splitPublicSourceDirs?.forEach { apkFilePath ->
                        getZipFilter(apkFilePath, ZIP_FILTER_TYPE).use {
                            val apkInfo = try {
                                ApkInfo.getApkInfo(
                                    locale,
                                    it,
                                    requestParseManifestXmlTagForApkType = GET_APK_TYPE,
                                    requestParseResources = VALIDATE_RESOURCES
                                )
                            } catch (e: Exception) {
                                Log.e(
                                    "AppLog",
                                    "can't parse apk of \"$packageName\" in $apkFilePath - exception:$e"
                                )
                                e.printStackTrace()
                                return@use
                            }
                            when {
                                apkInfo == null -> Log.e("AppLog", "can\'t parse apk:$apkFilePath")
                                GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.UNKNOWN -> Log.e(
                                    "AppLog",
                                    "can\'t get apk type: $apkFilePath"
                                )
                                GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.STANDALONE -> Log.e(
                                    "AppLog",
                                    "detected as standalone, but in fact is split apk: $apkFilePath"
                                )
                                GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT -> Log.e(
                                    "AppLog",
                                    "detected as base of split apks, but in fact is split apk: $apkFilePath"
                                )
                                GET_APK_TYPE && apkInfo.apkType == ApkInfo.ApkType.BASE_OF_SPLIT_OR_STANDALONE -> Log.e(
                                    "AppLog",
                                    "detected as base/standalone apk, but in fact is split apk: $apkFilePath"
                                )
                                else -> {
                                    val apkMeta = apkInfo.apkMetaTranslator.apkMeta
                                    val apkMetaTranslator = apkInfo.apkMetaTranslator
                                    when {
                                        packageInfo.packageName != apkMeta.packageName -> Log.e(
                                            "AppLog",
                                            "apk package name is different for $apkFilePath : correct one is: $packageName vs found: ${apkMeta.packageName}"
                                        )
                                        versionCodeCompat(packageInfo) != apkMeta.versionCode -> Log.e(
                                            "AppLog",
                                            "apk version code is different for $apkFilePath : correct one is: ${
                                                versionCodeCompat(packageInfo)
                                            } vs found: ${apkMeta.versionCode}"
                                        )
                                        else -> Log.d(
                                            "AppLog",
                                            "apk data of $apkFilePath : ${apkMeta.packageName}, ${apkMeta.versionCode}, ${apkMeta.versionName}, ${apkMeta.name}, ${apkMetaTranslator.iconPaths}"
                                        )
                                    }

                                }
                            }
                        }
                        ++apksHandledSoFar
                    }
                }
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
        }
    }

    companion object {
        fun versionCodeCompat(packageInfo: PackageInfo) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()

        enum class ZipFilterType {
            //this is the order from fastest to slowest, according to my tests:
            ZipFileFilter,
            ApacheZipArchiveInputStreamFilter,
            ZipInputStreamFilter,
            ApacheZipFileFilter
        }

        fun getZipFilter(apkFilePath: String, zipFilterType: ZipFilterType): AbstractZipFilter {
            return when (zipFilterType) {
                ZipFilterType.ZipFileFilter -> ZipFileFilter(ZipFile(apkFilePath))
                ZipFilterType.ApacheZipFileFilter -> ApacheZipFileFilter(
                    org.apache.commons.compress.archivers.zip.ZipFile(
                        apkFilePath
                    )
                )
                ZipFilterType.ApacheZipArchiveInputStreamFilter ->
                    ApacheZipArchiveInputStreamFilter(
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
