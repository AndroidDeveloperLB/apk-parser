package com.lb.apkparserdemo.apk_info

import net.dongliu.apk.parser.bean.DeviceConfig
import net.dongliu.apk.parser.parser.ApkMetaTranslator
import net.dongliu.apk.parser.parser.BinaryXmlParser
import net.dongliu.apk.parser.parser.CompositeXmlStreamer
import net.dongliu.apk.parser.parser.ResourceTableParser
import net.dongliu.apk.parser.parser.XmlTranslator
import net.dongliu.apk.parser.struct.AndroidConstants
import net.dongliu.apk.parser.struct.resource.ResourceTable
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Holds core information about an APK parsed from its manifest and resources.
 * Provides functionality to parse both standalone and split APKs, merging information when necessary.
 *
 * @property xmlTranslator Translates binary XML to human-readable format.
 * @property apkMetaTranslator Translates binary manifest data to metadata objects.
 * @property apkType Indicates whether the APK is a base/standalone or a split APK.
 * @property resourceTable The table of resources extracted from the APK.
 * @property allLocales A set of all locales supported by this APK and its merged splits.
 */
class ApkInfo(
        val xmlTranslator: XmlTranslator,
        val apkMetaTranslator: ApkMetaTranslator,
        val apkType: ApkType,
        val resourceTable: ResourceTable,
        val allLocales: Set<Locale> = emptySet()
) {
    /**
     * Enum defining the possible types of an APK file.
     */
    enum class ApkType {
        /** A split APK that belongs to an app. */
        Split,

        /** The main/base APK of an app or a standalone APK. */
        BaseOfSplitOrStandalone,

        /** Type could not be determined. */
        Unknown
    }

    companion object {

        /**
         * Parses a single APK (or base + splits) to create an [ApkInfo] instance.
         *
         * @param deviceConfig Configuration for the device to tailor parsing (e.g., density, locale).
         * @param baseZipFilter The filter used to read the base APK.
         * @param extraZipFilters Optional list of filters for split APKs to merge resources from.
         * @param requestParseResources Whether to parse the resources.arsc file.
         * @param masterResourceTable An optional pre-existing resource table to use.
         * @param baseManifestBytes Optional pre-read manifest bytes for the base APK.
         * @return An [ApkInfo] instance if parsing was successful, null otherwise.
         */
        @Suppress("SameParameterValue")
        fun internalGetApkInfo(
                deviceConfig: DeviceConfig?,
                baseZipFilter: AbstractZipFilter,
                extraZipFilters: List<AbstractZipFilter> = emptyList(),
                requestParseResources: Boolean = false,
                masterResourceTable: ResourceTable? = null,
                baseManifestBytes: ByteArray? = null
        ): ApkInfo? {
            val manifestBytes: ByteArray
            val resourcesByteBuffer: ByteBuffer?

            if (baseManifestBytes != null) {
                manifestBytes = baseManifestBytes
                resourcesByteBuffer = if (requestParseResources && masterResourceTable == null) baseZipFilter.getByteBufferForEntry(AndroidConstants.RESOURCE_FILE) else null
            } else {
                val mandatoryFilesToCheck = hashSetOf(AndroidConstants.MANIFEST_FILE)
                val extraFilesToCheck = if (requestParseResources && masterResourceTable == null) hashSetOf(AndroidConstants.RESOURCE_FILE) else null

                if (baseZipFilter.isSeekable) {
                    val byteArrayForEntries = baseZipFilter.getByteArrayForEntries(mandatoryFilesToCheck, null)
                    if (byteArrayForEntries == null) {
//                        android.util.Log.e("AppLog", "icon fetching: internalGetApkInfo failed to get mandatory entries from seekable baseZipFilter: $baseZipFilter")
                        return null
                    }
                    manifestBytes = byteArrayForEntries[AndroidConstants.MANIFEST_FILE]
                            ?: return null
                    resourcesByteBuffer = if (requestParseResources && masterResourceTable == null) baseZipFilter.getByteBufferForEntry(AndroidConstants.RESOURCE_FILE) else null
//                    if (requestParseResources && masterResourceTable == null && resourcesByteBuffer == null) {
//                        android.util.Log.d("AppLog", "icon fetching: resources.arsc not found in seekable base APK")
//                    }
                } else {
                    val byteArrayForEntries = baseZipFilter.getByteArrayForEntries(mandatoryFilesToCheck, extraFilesToCheck)
                    if (byteArrayForEntries == null) {
//                        android.util.Log.e("AppLog", "icon fetching: internalGetApkInfo failed to get mandatory entries from non-seekable baseZipFilter: $baseZipFilter")
                        return null
                    }
                    manifestBytes = byteArrayForEntries[AndroidConstants.MANIFEST_FILE]
                            ?: return null
                    resourcesByteBuffer = byteArrayForEntries[AndroidConstants.RESOURCE_FILE]?.let {
//                        android.util.Log.d("AppLog", "icon fetching: found resources.arsc in non-seekable base APK (size: ${it.size})")
                        ByteBuffer.wrap(it)
                    }
                }
            }

            val xmlTranslator = XmlTranslator()
            val allLocales = mutableSetOf<Locale>()
            val resourceTable: ResourceTable =
                    if (masterResourceTable != null) {
                        allLocales.addAll(masterResourceTable.locales)
                        masterResourceTable
                    } else {
                        val table = if (resourcesByteBuffer == null) {
                            ResourceTable(null)
                        } else {
                            val resourceTableParser = ResourceTableParser(resourcesByteBuffer)
                            resourceTableParser.parse()
                            allLocales.addAll(resourceTableParser.locales)
                            resourceTableParser.resourceTable
                        }
                        // Merge extra resource tables if requested
                        if (requestParseResources) {
                            for (extraFilter in extraZipFilters) {
                                val extraResourcesByteBuffer = if (extraFilter.isSeekable) {
                                    extraFilter.getByteBufferForEntry(AndroidConstants.RESOURCE_FILE)
                                } else {
                                    extraFilter.getByteArrayForEntries(emptySet(), hashSetOf(AndroidConstants.RESOURCE_FILE))
                                            ?.get(AndroidConstants.RESOURCE_FILE)?.let { ByteBuffer.wrap(it) }
                                }
                                if (extraResourcesByteBuffer != null) {
                                    try {
//                                        android.util.Log.d("AppLog", "icon fetching: merging resources.arsc from extra filter (size: ${extraResourcesByteBuffer.remaining()})")
                                        val extraParser = ResourceTableParser(extraResourcesByteBuffer)
                                        extraParser.parse()
                                        // Merge all splits to ensure we have all translations for label/icon resolution.
                                        allLocales.addAll(extraParser.locales)
                                        table.merge(extraParser.resourceTable)
                                    } catch (e: Exception) {
                                    }
                                }
                            }
                        }
                        table
                    }
            val apkMetaTranslator = ApkMetaTranslator(resourceTable, deviceConfig)
            val binaryXmlParser = BinaryXmlParser(
                    ByteBuffer.wrap(manifestBytes), resourceTable,
                    CompositeXmlStreamer(xmlTranslator, apkMetaTranslator),
                    deviceConfig
            )
            try {
                binaryXmlParser.parse()
            } catch (e: Throwable) {
//                android.util.Log.e("AppLog", "icon fetching: CRITICAL error during binaryXmlParser.parse()", e)
                throw e
            }
            val apkMeta = apkMetaTranslator.apkMeta
            //split APK files have "split" in the root of the manifest tag. Base/standalone APK don't have it.
            if (apkMeta.split.isNullOrEmpty())
                return ApkInfo(xmlTranslator, apkMetaTranslator, ApkType.BaseOfSplitOrStandalone, resourceTable, allLocales)
            return ApkInfo(xmlTranslator, apkMetaTranslator, ApkType.Split, resourceTable, allLocales)
        }

        /**
         * Parses and consolidates information from multiple [AbstractZipFilter]s representing a single app (base + splits).
         *
         * @param deviceConfig Configuration for the device.
         * @param filters List of filters representing the set of APKs.
         * @param requestParseResources Whether to parse resources.
         * @return A consolidated [ApkInfo] instance.
         */
        fun getConsolidatedApkInfo(
                deviceConfig: DeviceConfig?, filters: List<AbstractZipFilter>,
                requestParseResources: Boolean = false
        ): ApkInfo? {
            if (filters.isEmpty()) return null
            if (filters.size == 1)
                return internalGetApkInfo(deviceConfig,
                        filters[0], emptyList(),
                        requestParseResources)
            var baseFilter: AbstractZipFilter? = null
            val extraFilters = mutableListOf<AbstractZipFilter>()
            val manifestBytesMap = mutableMapOf<AbstractZipFilter, ByteArray>()
            for (filter in filters) {
                val manifestBytes = filter.getByteArrayForEntries(hashSetOf(AndroidConstants.MANIFEST_FILE))
                        ?.get(AndroidConstants.MANIFEST_FILE)
                if (manifestBytes != null) {
                    manifestBytesMap[filter] = manifestBytes
                    val apkMetaTranslator = ApkMetaTranslator(ResourceTable(null), deviceConfig)
                    val binaryXmlParser = BinaryXmlParser(
                            ByteBuffer.wrap(manifestBytes), ResourceTable(null),
                            apkMetaTranslator, deviceConfig)
                    try {
                        binaryXmlParser.parse()
                        if (apkMetaTranslator.apkMeta.split.isNullOrEmpty()) {
                            baseFilter = filter
                        } else {
                            extraFilters.add(filter)
                        }
                    } catch (e: Exception) {
                        extraFilters.add(filter)
                    }
                } else {
                    extraFilters.add(filter)
                }
            }

            if (baseFilter == null) {
                if (extraFilters.isEmpty()) return null
                baseFilter = extraFilters.removeAt(0)
            } else {
                // Ensure it's not in extraFilters if it was added there somehow
                extraFilters.remove(baseFilter)
            }
            return internalGetApkInfo(deviceConfig, baseFilter, extraFilters, requestParseResources, null, manifestBytesMap[baseFilter])
        }

        /**
         * Consolidates a base [ApkInfo] with additional [ApkInfo] objects (splits) and a filter.
         *
         * @param deviceConfig Configuration for the device.
         * @param baseApkInfo The initial [ApkInfo] for the base APK.
         * @param baseFilter The filter for the base APK to re-parse its manifest with the new master resource table.
         * @param extraApkInfos Additional [ApkInfo] objects from split APKs.
         * @return A consolidated [ApkInfo] instance.
         */
        fun getConsolidatedApkInfo(
                deviceConfig: DeviceConfig?,
                baseApkInfo: ApkInfo,
                baseFilter: AbstractZipFilter,
                extraApkInfos: List<ApkInfo>
        ): ApkInfo? {
            val manifestBytes = baseFilter.getByteArrayForEntries(hashSetOf(AndroidConstants.MANIFEST_FILE))
                    ?.get(AndroidConstants.MANIFEST_FILE) ?: return null

            val masterTable = baseApkInfo.resourceTable
            val allLocales = baseApkInfo.allLocales.toMutableSet()

            for (extra in extraApkInfos) {
                masterTable.merge(extra.resourceTable)
                allLocales.addAll(extra.allLocales)
            }

            val xmlTranslator = XmlTranslator()
            val apkMetaTranslator = ApkMetaTranslator(masterTable, deviceConfig)
            val binaryXmlParser = BinaryXmlParser(
                    ByteBuffer.wrap(manifestBytes), masterTable,
                    CompositeXmlStreamer(xmlTranslator, apkMetaTranslator),
                    deviceConfig
            )
            binaryXmlParser.parse()

            return ApkInfo(
                    xmlTranslator,
                    apkMetaTranslator,
                    baseApkInfo.apkType,
                    masterTable,
                    allLocales
            )
        }
    }
}
