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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (xmlIconsPath in xmlIconsPaths) {
                    val bytes = byteArrayForEntries[xmlIconsPath] ?: continue
                    try {
                        val adaptiveIconParser = AdaptiveIconParser()
                        val buffer = ByteBuffer.wrap(bytes)
                        val binaryXmlParser = BinaryXmlParser(buffer, apkInfo.resourceTable, adaptiveIconParser, locale)
                        binaryXmlParser.parse()
                        val rootTag = adaptiveIconParser.rootTag
                        if (rootTag == "adaptive-icon") {
                            val backgroundPath = adaptiveIconParser.background
                            val foregroundPath = adaptiveIconParser.foreground
                            if (!backgroundPath.isNullOrBlank() && !foregroundPath.isNullOrBlank()) {
                                filterGenerator.generateZipFilter().use { adaptiveIconZipFilter ->
                                    val pathsToFetch = hashSetOf<String>()
                                    if (!backgroundPath.startsWith("#")) pathsToFetch.add(backgroundPath)
                                    if (!foregroundPath.startsWith("#")) pathsToFetch.add(foregroundPath)
                                    val adaptiveIconByteArrayForEntries = if (pathsToFetch.isNotEmpty()) adaptiveIconZipFilter.getByteArrayForEntries(pathsToFetch) ?: emptyMap() else emptyMap()
                                    val backgroundDrawable = fetchDrawable(context, backgroundPath, adaptiveIconByteArrayForEntries[backgroundPath], apkInfo, locale, requestedAppIconSize)
                                    val foregroundDrawable = fetchDrawable(context, foregroundPath, adaptiveIconByteArrayForEntries[foregroundPath], apkInfo, locale, requestedAppIconSize)
                                    if (backgroundDrawable != null && foregroundDrawable != null) {
                                        return AdaptiveIconDrawable(backgroundDrawable, foregroundDrawable).toBitmap(requestedAppIconSize, requestedAppIconSize)
                                    }
                                }
                            }
                        } else if (rootTag == "layer-list") {
                            val drawablesPaths = adaptiveIconParser.drawables
                            if (drawablesPaths.isNotEmpty()) {
                                filterGenerator.generateZipFilter().use { layerZipFilter ->
                                    val pathsToFetch = drawablesPaths.filter { !it.startsWith("#") }.toHashSet()
                                    val drawablesBytes = if (pathsToFetch.isNotEmpty()) layerZipFilter.getByteArrayForEntries(pathsToFetch) ?: emptyMap() else emptyMap()
                                    val drawables = drawablesPaths.mapNotNull { path -> fetchDrawable(context, path, drawablesBytes[path], apkInfo, locale, requestedAppIconSize) }
                                    if (drawables.isNotEmpty()) {
                                        return LayerDrawable(drawables.toTypedArray()).toBitmap(requestedAppIconSize, requestedAppIconSize)
                                    }
                                }
                            }
                        } else if (rootTag == "vector" || rootTag == "selector") {
                            val drawable = fetchDrawable(context, xmlIconsPath, bytes, apkInfo, locale, requestedAppIconSize)
                            if (drawable != null) return drawable.toBitmap(requestedAppIconSize, requestedAppIconSize)
                        }
                    } catch (e: Exception) {
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
