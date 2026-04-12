package com.lb.apkparserdemo.apk_info.app_icon

import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
import android.graphics.ImageDecoder
import androidx.core.graphics.drawable.toBitmap
import com.lb.apkparserdemo.apk_info.*
import net.dongliu.apk.parser.bean.IconPath
import net.dongliu.apk.parser.parser.*
import net.dongliu.apk.parser.struct.resource.Densities
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

object ApkIconFetcher {
    interface ZipFilterCreator {
        fun generateZipFilter(): AbstractZipFilter
    }

    fun getApkIcon(
        context: Context,
        locales: List<Locale>,
        filterGenerator: ZipFilterCreator,
        apkInfo: ApkInfo,
        requestedAppIconSize: Int = 0
    ): Bitmap? {
        val iconPaths = apkInfo.apkMetaTranslator.iconPaths
        if (iconPaths.isEmpty()) {
            return null
        }

        val densityDpi = context.resources.displayMetrics.densityDpi
        val sortedIconPaths = iconPaths.sortedWith(Comparator { o1: IconPath, o2: IconPath ->
            if (o1.density == o2.density) return@Comparator 0
            if (o1.density == Densities.ANY) return@Comparator -1
            if (o2.density == Densities.ANY) return@Comparator 1
            if (o1.density == Densities.NONE) return@Comparator -1
            if (o2.density == Densities.NONE) return@Comparator 1
            if (o1.density == Densities.DEFAULT) return@Comparator 1
            if (o2.density == Densities.DEFAULT) return@Comparator -1

            val diff1 = abs(o1.density - densityDpi)
            val diff2 = abs(o2.density - densityDpi)
            if (diff1 != diff2) return@Comparator diff1.compareTo(diff2)
            o2.density.compareTo(o1.density)
        })

        val colorIconsPaths = sortedIconPaths.mapNotNull { it.path }.filter { it.startsWith("#") }.distinct()
        val otherIconPaths = sortedIconPaths.mapNotNull { it.path }.filter { !it.startsWith("#") }.distinct()

        for (path in otherIconPaths) {
            filterGenerator.generateZipFilter().use { filter ->
                val bytes = filter.getByteArrayForEntries(hashSetOf(path))?.get(path)
                if (bytes != null) {
                    try {
                        val drawable = fetchDrawable(context, path, bytes, apkInfo, locales, filterGenerator, requestedAppIconSize)
                        if (drawable != null) {
                            return drawable.toBitmap(requestedAppIconSize, requestedAppIconSize)
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        for (colorPath in colorIconsPaths) {
            try {
                val color = Color.parseColor(colorPath)
                val bitmap = Bitmap.createBitmap(requestedAppIconSize, requestedAppIconSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
                return bitmap
            } catch (e: Exception) {}
        }
        return null
    }

    private fun fetchDrawable(
        context: Context,
        path: String,
        bytes: ByteArray?,
        apkInfo: ApkInfo,
        locales: List<Locale>,
        filterGenerator: ZipFilterCreator,
        requestedAppIconSize: Int
    ): Drawable? {
        if (path.startsWith("#")) {
            return try {
                ColorDrawable(Color.parseColor(path))
            } catch (e: Exception) {
                null
            }
        }
        if (path.startsWith("?")) {
            try {
                val attrId = if (path.contains("0x")) path.substringAfter("0x").toLong(16) else 0L
                if (attrId != 0L) {
                    val resources = apkInfo.resourceTable.getResourcesById(attrId)
                    if (resources.isNotEmpty()) {
                        for (res in resources) {
                            val value = res.resourceEntry.toStringValue(apkInfo.resourceTable, locales)
                            if (value != null && (value.startsWith("#") || value.startsWith("res/"))) {
                                if (value.startsWith("#")) return ColorDrawable(Color.parseColor(value))
                                
                                filterGenerator.generateZipFilter().use { filter ->
                                    val subBytes = filter.getByteArrayForEntries(emptySet(), hashSetOf(value))?.get(value)
                                    return fetchDrawable(context, value, subBytes, apkInfo, locales, filterGenerator, requestedAppIconSize)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        if (path.startsWith("resourceId:")) {
            val resId = try {
                path.substringAfter("0x").toLong(16).toInt()
            } catch (e: Exception) {
                0
            }
            if (resId != 0) {
                val packageId = resId shr 24
                if (packageId == 0x01) {
                    try {
                        val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(context.resources, resId, null)
                        if (drawable != null) return drawable
                    } catch (e: Exception) {}
                } else {
                    try {
                        val resources = apkInfo.resourceTable.getResourcesById(resId.toLong())
                        if (resources.isNotEmpty()) {
                            for (res in resources) {
                                val value = res.resourceEntry.toStringValue(apkInfo.resourceTable, locales)
                                if (value != null && value != path) {
                                    if (value.startsWith("#")) return ColorDrawable(Color.parseColor(value))
                                    filterGenerator.generateZipFilter().use { filter ->
                                        val subBytes = if (isZipPath(value)) filter.getByteArrayForEntries(emptySet(), hashSetOf(value))?.get(value) else null
                                        return fetchDrawable(context, value, subBytes, apkInfo, locales, filterGenerator, requestedAppIconSize)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }
        if (bytes == null) return null
        if (!path.endsWith(".xml", true)) {
            return getAppIconFromByteArray(bytes, requestedAppIconSize, path)?.let {
                BitmapDrawable(context.resources, it)
            }
        }

        try {
            val adaptiveIconParser = AdaptiveIconParser()
            val buffer = ByteBuffer.wrap(bytes)
            val preferredLocale = locales.getOrNull(0) ?: Locale.getDefault()
            val binaryXmlParser = BinaryXmlParser(buffer, apkInfo.resourceTable, adaptiveIconParser, preferredLocale)
            binaryXmlParser.parse()
            val rootTag = adaptiveIconParser.rootTag

            if (rootTag == "adaptive-icon" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val backgroundPaths = adaptiveIconParser.backgroundDrawables
                var foregroundPaths = adaptiveIconParser.foregroundDrawables
                val monochromePaths = adaptiveIconParser.monochromeDrawables
                if (foregroundPaths.isEmpty() && !monochromePaths.isEmpty()) {
                    foregroundPaths = monochromePaths
                }
                
                if (adaptiveIconParser.hasInlineContent()) {
                    return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, preferredLocale) { subPath ->
                        filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                    }
                }

                if (foregroundPaths.isNotEmpty()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = hashSetOf<String>()
                        pathsToFetch.addAll(backgroundPaths.filter { isZipPath(it) })
                        pathsToFetch.addAll(foregroundPaths.filter { isZipPath(it) })
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(emptySet(), pathsToFetch) ?: emptyMap() else emptyMap()

                        val backgroundDrawables = backgroundPaths.mapNotNull { p ->
                            fetchDrawable(context, p, byteArrayForEntries[p], apkInfo, locales, filterGenerator, requestedAppIconSize)
                        }
                        
                        val foregroundDrawables = foregroundPaths.mapNotNull { p ->
                            fetchDrawable(context, p, byteArrayForEntries[p], apkInfo, locales, filterGenerator, requestedAppIconSize)
                        }
                        
                        if (foregroundDrawables.isNotEmpty()) {
                            val bg = when {
                                backgroundDrawables.size > 1 -> LayerDrawable(backgroundDrawables.toTypedArray())
                                backgroundDrawables.size == 1 -> backgroundDrawables[0]
                                else -> ColorDrawable(Color.TRANSPARENT)
                            }
                            val fg = if (foregroundDrawables.size > 1) LayerDrawable(foregroundDrawables.toTypedArray()) else foregroundDrawables[0]
                            return AdaptiveIconDrawable(bg, fg)
                        }
                    }
                }
                return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, preferredLocale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
            } else if (rootTag == "layer-list") {
                val drawablesPaths = adaptiveIconParser.drawables
                if (drawablesPaths.isNotEmpty()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = drawablesPaths.filter { isZipPath(it) }.toHashSet()
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(emptySet(), pathsToFetch) ?: emptyMap() else emptyMap()
                        val drawables = drawablesPaths.mapNotNull { layerPath ->
                            fetchDrawable(context, layerPath, byteArrayForEntries[layerPath], apkInfo, locales, filterGenerator, requestedAppIconSize)
                        }
                        if (drawables.isNotEmpty()) {
                            return LayerDrawable(drawables.toTypedArray())
                        }
                    }
                }
                return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, preferredLocale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
            } else if (rootTag == "bitmap" || rootTag == "nine-patch" || rootTag == "inset" || rootTag == "clip" || rootTag == "scale" || rootTag == "rotate") {
                val innerPath = adaptiveIconParser.drawables.firstOrNull()
                if (!innerPath.isNullOrBlank()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val srcBytes = if (isZipPath(innerPath)) filter.getByteArrayForEntries(hashSetOf(innerPath))?.get(innerPath) else null
                        val drawable = fetchDrawable(context, innerPath, srcBytes, apkInfo, locales, filterGenerator, requestedAppIconSize)
                        if (drawable != null) return drawable
                    }
                }
                return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, preferredLocale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
            } else {
                val drawable = XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, preferredLocale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
                if (drawable == null) {
                    try {
                        val xmlTranslator = XmlTranslator()
                        val fallbackBuffer = ByteBuffer.wrap(bytes)
                        val fallbackBinaryXmlParser = BinaryXmlParser(fallbackBuffer, apkInfo.resourceTable, xmlTranslator, preferredLocale)
                        fallbackBinaryXmlParser.parse()
                        val xml = xmlTranslator.xml
                        return XmlDrawableParser.tryParseDrawable(context, xml)
                    } catch (e: Exception) {}
                }
                return drawable
            }
        } catch (e: Exception) {}
        return null
    }

    private fun isZipPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("#")) return false
        if (path.startsWith("resourceId:")) return false
        return true
    }

    private fun getAppIconFromByteArray(bytes: ByteArray, requestedAppIconSize: Int, path: String): Bitmap? {
        if (requestedAppIconSize > 0) {
            val bitmapOptions = BitmapFactory.Options()
            bitmapOptions.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
            BitmapHelper.prepareBitmapOptionsForSampling(
                bitmapOptions,
                requestedAppIconSize,
                requestedAppIconSize
            )
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
            if (bitmap != null) return bitmap
        } else {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) return bitmap
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    if (requestedAppIconSize > 0) {
                        decoder.setTargetSize(requestedAppIconSize, requestedAppIconSize)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {}
        }
        return null
    }
}
