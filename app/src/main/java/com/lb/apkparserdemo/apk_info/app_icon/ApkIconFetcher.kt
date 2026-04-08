package com.lb.apkparserdemo.apk_info.app_icon

import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.lb.apkparserdemo.apk_info.*
import net.dongliu.apk.parser.bean.IconPath
import net.dongliu.apk.parser.parser.*
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

object ApkIconFetcher {
    interface ZipFilterCreator {
        fun generateZipFilter(): AbstractZipFilter
    }

    fun getApkIcon(
        context: Context,
        locale: Locale,
        filterGenerator: ZipFilterCreator,
        apkInfo: ApkInfo,
        requestedAppIconSize: Int = 0
    ): Bitmap? {
        val iconPaths = apkInfo.apkMetaTranslator.iconPaths
        if (iconPaths.isEmpty())
            return null
        val resources = context.resources
        val densityDpi = resources.displayMetrics.densityDpi
        var bestDividedDensityIconImage: IconPath? = null
        var bestDivider = 0
        var closestDensityMatchIconImage: IconPath? = null
        var bestDensityDiff = -1
        val xmlIconsPaths = HashSet<String>()
        val colorIconsPaths = HashSet<String>()
        for (iconPath in iconPaths) {
            val path = iconPath.path ?: continue
            if (path.startsWith("#")) {
                colorIconsPaths.add(path)
                continue
            }
            if (path.endsWith(".xml", true)) {
                xmlIconsPaths.add(path)
                continue
            }
            if (iconPath.density % densityDpi == 0) {
                //divided nicely
                val divider = iconPath.density / densityDpi
                if (divider < bestDivider || bestDivider < 0) {
                    bestDivider = divider
                    bestDividedDensityIconImage = iconPath
                }
                if (bestDivider == 1)
                    break
            }
            val densityDiff = abs(iconPath.density - densityDpi)
            if (bestDensityDiff < 0 || densityDiff < bestDensityDiff) {
                bestDensityDiff = densityDiff
                closestDensityMatchIconImage = iconPath
            }
        }
        val iconsToFetch = HashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            iconsToFetch.addAll(xmlIconsPaths)
        val imageIconPath = bestDividedDensityIconImage?.path
            ?: closestDensityMatchIconImage?.path
        imageIconPath?.let { iconsToFetch.add(it) }
        if (iconsToFetch.isEmpty() && colorIconsPaths.isEmpty())
            return null
        filterGenerator.generateZipFilter().use { filter: AbstractZipFilter ->
            val byteArrayForEntries = filter.getByteArrayForEntries(iconsToFetch) ?: emptyMap()
//            val requestedAppIconSize = getAppIconSize(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (xmlIconsPath in xmlIconsPaths) {
                    //prefer to try to parse XML first
                    val bytes = byteArrayForEntries[xmlIconsPath] ?: continue
                    try {
                        val adaptiveIconParser = AdaptiveIconParser()
                        val buffer = ByteBuffer.wrap(bytes)
                        val binaryXmlParser =
                            BinaryXmlParser(
                                buffer, apkInfo.resourceTable,
                                adaptiveIconParser, locale
                            )
                        binaryXmlParser.parse()
                        val rootTag = adaptiveIconParser.rootTag
                        if (rootTag == "adaptive-icon") {
                            val backgroundPath: String? = adaptiveIconParser.background
                            val foregroundPath: String? = adaptiveIconParser.foreground
                            if (!backgroundPath.isNullOrBlank() && !foregroundPath.isNullOrBlank()) {
                                filterGenerator.generateZipFilter()
                                    .use { adaptiveIconZipFilter: AbstractZipFilter ->
                                        adaptiveIconZipFilter.getByteArrayForEntries(
                                            hashSetOf(
                                                backgroundPath,
                                                foregroundPath
                                            )
                                        )?.let { adaptiveIconByteArrayForEntries ->
                                            val backgroundIconBytes =
                                                adaptiveIconByteArrayForEntries[backgroundPath]
                                            val foregroundIconBytes =
                                                adaptiveIconByteArrayForEntries[foregroundPath]
                                            val backgroundDrawable =
                                                fetchDrawable(
                                                    context,
                                                    backgroundPath,
                                                    backgroundIconBytes,
                                                    apkInfo,
                                                    locale,
                                                    requestedAppIconSize
                                                )
                                            val foregroundDrawable =
                                                fetchDrawable(
                                                    context,
                                                    foregroundPath,
                                                    foregroundIconBytes,
                                                    apkInfo,
                                                    locale,
                                                    requestedAppIconSize
                                                )
                                            if (backgroundDrawable != null && foregroundDrawable != null) {
                                                val adaptiveIconDrawable = AdaptiveIconDrawable(
                                                    backgroundDrawable,
                                                    foregroundDrawable
                                                )
                                                return adaptiveIconDrawable.toBitmap(
                                                    requestedAppIconSize,
                                                    requestedAppIconSize
                                                )
                                            }
                                        }
                                    }
                            }
                        } else if (rootTag == "vector" || rootTag == "layer-list" || rootTag == "selector") {
                            val drawable = fetchDrawable(
                                context,
                                xmlIconsPath,
                                bytes,
                                apkInfo,
                                locale,
                                requestedAppIconSize
                            )
                            if (drawable != null) {
                                return drawable.toBitmap(
                                    requestedAppIconSize,
                                    requestedAppIconSize
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            if (imageIconPath != null) {
                val bytes = byteArrayForEntries[imageIconPath]
                if (bytes != null) {
                    val bitmap = getAppIconFromByteArray(bytes, requestedAppIconSize)
                    if (bitmap != null) return bitmap
                }
            }
            for (colorPath in colorIconsPaths) {
                try {
                    val color = Color.parseColor(colorPath)
                    val bitmap = Bitmap.createBitmap(
                        requestedAppIconSize,
                        requestedAppIconSize,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(color)
                    return bitmap
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    private fun fetchDrawable(
        context: Context,
        path: String,
        bytes: ByteArray?,
        apkInfo: ApkInfo,
        locale: Locale,
        requestedAppIconSize: Int
    ): Drawable? {
        if (path.startsWith("#")) {
            return try {
                ColorDrawable(Color.parseColor(path))
            } catch (e: Exception) {
                null
            }
        }
        if (bytes == null) return null
        if (!path.endsWith(".xml", true)) {
            return getAppIconFromByteArray(bytes, requestedAppIconSize)?.let {
                BitmapDrawable(context.resources, it)
            }
        }
        // first try binary parsing (can be faster but might fail if it has unresolved refs)
        var drawable = XmlDrawableParser.tryParseDrawable(context, bytes)
        if (drawable == null) {
            // fallback to text XML translation which resolves references
            try {
                val xmlTranslator = XmlTranslator()
                val buffer = ByteBuffer.wrap(bytes)
                val binaryXmlParser =
                    BinaryXmlParser(buffer, apkInfo.resourceTable, xmlTranslator, locale)
                binaryXmlParser.parse()
                val xml = xmlTranslator.xml
                drawable = XmlDrawableParser.tryParseDrawable(context, xml)
            } catch (e: Exception) {
            }
        }
        return drawable
    }

    private fun getAppIconFromByteArray(bytes: ByteArray, requestedAppIconSize: Int): Bitmap? {
        if (requestedAppIconSize > 0) {
            val bitmapOptions = BitmapFactory.Options()
            bitmapOptions.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
            BitmapHelper.prepareBitmapOptionsForSampling(
                bitmapOptions,
                requestedAppIconSize,
                requestedAppIconSize
            )
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
