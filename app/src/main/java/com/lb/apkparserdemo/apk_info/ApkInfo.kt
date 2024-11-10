package com.lb.apkparserdemo.apk_info

import android.content.pm.PackageInfo
import android.os.Bundle
import net.dongliu.apk.parser.parser.*
import net.dongliu.apk.parser.struct.AndroidConstants
import net.dongliu.apk.parser.struct.resource.ResourceTable
import java.nio.ByteBuffer
import java.util.Locale

class ApkInfo(
    val xmlTranslator: XmlTranslator,
    val apkMetaTranslator: ApkMetaTranslator,
    val apkType: ApkType,
    val resourceTable: ResourceTable
) {
    enum class ApkType {
        STANDALONE, BASE_OF_SPLIT, SPLIT, BASE_OF_SPLIT_OR_STANDALONE, UNKNOWN
    }

    companion object {
        fun getApkType(packageInfo: PackageInfo): ApkType {
            val metaData: Bundle = packageInfo.applicationInfo!!.metaData ?: return ApkType.UNKNOWN
            if (metaData.containsKey("com.android.vending.splits.required")) {
                val isSplitRequired =
                    metaData.getBoolean("com.android.vending.splits.required", false)
                return if (isSplitRequired) ApkType.BASE_OF_SPLIT else ApkType.BASE_OF_SPLIT_OR_STANDALONE
            }
            if (metaData.containsKey("com.android.vending.splits"))
                return ApkType.BASE_OF_SPLIT_OR_STANDALONE
            if (metaData.getBoolean("instantapps.clients.allowed", false))
                return ApkType.BASE_OF_SPLIT_OR_STANDALONE
            return ApkType.UNKNOWN
        }

        @Suppress("SameParameterValue")
        fun getApkInfo(
            locale: Locale,
            zipFilter: AbstractZipFilter,
            requestParseManifestXmlTagForApkType: Boolean = false,
            requestParseResources: Boolean = false
        ): ApkInfo? {
            val mandatoryFilesToCheck = hashSetOf(AndroidConstants.MANIFEST_FILE)
            val extraFilesToCheck =
                if (requestParseResources) hashSetOf(AndroidConstants.RESOURCE_FILE) else null
            val byteArrayForEntries =
                zipFilter.getByteArrayForEntries(mandatoryFilesToCheck, extraFilesToCheck)
                    ?: return null
            val manifestBytes: ByteArray = byteArrayForEntries[AndroidConstants.MANIFEST_FILE]
                ?: return null
            val resourcesBytes: ByteArray? = byteArrayForEntries[AndroidConstants.RESOURCE_FILE]
            val xmlTranslator = XmlTranslator()
            val resourceTable: ResourceTable =
                if (resourcesBytes == null)
                    ResourceTable(null)
                else {
                    val resourceTableParser = ResourceTableParser(ByteBuffer.wrap(resourcesBytes))
                    resourceTableParser.parse()
                    resourceTableParser.resourceTable
                    //                this.locales = resourceTableParser.locales
                }
            val apkMetaTranslator = ApkMetaTranslator(resourceTable, locale)
            val binaryXmlParser = BinaryXmlParser(
                ByteBuffer.wrap(manifestBytes), resourceTable,
                CompositeXmlStreamer(xmlTranslator, apkMetaTranslator), locale
            )
            binaryXmlParser.parse()
            var apkType: ApkType = ApkType.UNKNOWN
            if (requestParseManifestXmlTagForApkType) {
                val apkMeta = apkMetaTranslator.apkMeta
                val isSplitApk = !apkMeta.split.isNullOrEmpty()
                if (isSplitApk)
                    apkType = ApkType.SPLIT
                else {
                    //standalone or base of split apks
                    val isDefinitelyBaseApkOfSplit = apkMeta.isSplitRequired
                    if (isDefinitelyBaseApkOfSplit)
                        apkType = ApkType.BASE_OF_SPLIT
                    else {
                        val manifestXml = xmlTranslator.xml
                        apkType = ApkType.STANDALONE
                        try {
                            XmlTag.getXmlFromString(manifestXml)?.innerTagsAndContent?.forEach { manifestXmlItem: Any ->
                                //reach the "application" xml tag, and then iterate over its children
                                if (manifestXmlItem !is XmlTag || manifestXmlItem.tagName != "application")
                                    return@forEach
                                // find the value of `<meta-data android:name=""` or `<meta-data name=""` , and later perhaps of `android:value="` or `value="`
                                val innerTagsAndContent = manifestXmlItem.innerTagsAndContent
                                    ?: return@forEach
                                for (applicationXmlItem: Any in innerTagsAndContent) {
                                    // find the value of `<meta-data android:name=""` or `<meta-data name=""` , and later perhaps of `android:value="` or `value="`
                                    if (applicationXmlItem !is XmlTag || applicationXmlItem.tagName != "meta-data")
                                        continue
                                    val tagAttributes = applicationXmlItem.tagAttributes ?: continue
                                    val attributeValueForName = tagAttributes["android:name"]
                                        ?: tagAttributes["name"] ?: continue
                                    when (attributeValueForName) {
                                        "com.android.vending.splits" -> {
                                            apkType = ApkType.BASE_OF_SPLIT_OR_STANDALONE
                                        }

                                        "instantapps.clients.allowed" -> {
                                            val value = tagAttributes["android:value"]
                                                ?: tagAttributes["value"] ?: continue
                                            if (value != "false")
                                                apkType = ApkType.BASE_OF_SPLIT_OR_STANDALONE
                                        }

                                        "com.android.vending.splits.required" -> {
                                            val value = tagAttributes["android:value"]
                                                ?: tagAttributes["value"] ?: continue
                                            val isSplitRequired = value != "false"
                                            apkType =
                                                if (isSplitRequired) ApkType.BASE_OF_SPLIT else ApkType.BASE_OF_SPLIT_OR_STANDALONE
                                            break
                                        }
                                    }
                                }
                                return@forEach
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
//                            Log.e("AppLog", "failed to get apk type: $e")
                        }
                    }
                }
            }
            return ApkInfo(xmlTranslator, apkMetaTranslator, apkType, resourceTable)
        }
    }
}
