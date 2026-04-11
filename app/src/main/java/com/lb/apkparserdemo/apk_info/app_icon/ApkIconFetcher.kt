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
        locale: Locale,
        filterGenerator: ZipFilterCreator,
        apkInfo: ApkInfo,
        requestedAppIconSize: Int = 0
    ): Bitmap? {
        val iconPaths = apkInfo.apkMetaTranslator.iconPaths
        if (iconPaths.isEmpty()) {
            android.util.Log.d("AppLog", "icon fetching: no icon paths found in manifest")
            return null
        }

        val densityDpi = context.resources.displayMetrics.densityDpi

        // Custom sorting for density: ANY is best, then closest to target densityDpi
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
            // if same distance, prefer higher density
            return@Comparator o2.density.compareTo(o1.density)
        })

        // Filter out colors for now, try image/xml icons first
        val colorIconsPaths = sortedIconPaths.mapNotNull { it.path }.filter { it.startsWith("#") }.distinct()
        val otherIconPaths = sortedIconPaths.mapNotNull { it.path }.filter { !it.startsWith("#") }.distinct()

        for (path in otherIconPaths) {
            android.util.Log.d("AppLog", "icon fetching: attempting path: $path")
            filterGenerator.generateZipFilter().use { filter ->
                val bytes = filter.getByteArrayForEntries(hashSetOf(path))?.get(path)
                if (bytes != null) {
                    try {
                        val drawable = fetchDrawable(context, path, bytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                        if (drawable != null) {
                            android.util.Log.d("AppLog", "icon fetching: successfully decoded: $path")
                            return drawable.toBitmap(requestedAppIconSize, requestedAppIconSize)
                        } else {
                            android.util.Log.d("AppLog", "icon fetching: failed to decode: $path")
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("AppLog", "icon fetching: exception decoding $path: ${e.message}")
                    }
                } else {
                    android.util.Log.d("AppLog", "icon fetching: path not found in zip: $path")
                }
            }
        }

        // Try colors if everything else failed
        for (colorPath in colorIconsPaths) {
            android.util.Log.d("AppLog", "icon fetching: using color icon: $colorPath")
            try {
                val color = Color.parseColor(colorPath)
                val bitmap = Bitmap.createBitmap(requestedAppIconSize, requestedAppIconSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
                return bitmap
            } catch (e: Exception) {
                android.util.Log.d("AppLog", "icon fetching: failed to use color $colorPath: ${e.message}")
            }
        }
        android.util.Log.d("AppLog", "icon fetching: all attempts failed")
        return null
    }

    private fun fetchDrawable(
        context: Context,
        path: String,
        bytes: ByteArray?,
        apkInfo: ApkInfo,
        locale: Locale,
        filterGenerator: ZipFilterCreator,
        requestedAppIconSize: Int
    ): Drawable? {
        android.util.Log.d("AppLog", "icon fetching: fetchDrawable path: $path")
        if (path.startsWith("#")) {
            return try {
                ColorDrawable(Color.parseColor(path))
            } catch (e: Exception) {
                null
            }
        }
        if (path.startsWith("?")) {
            // Attempt to resolve attribute reference
            try {
                val attrId = if (path.contains("0x")) path.substringAfter("0x").toLong(16) else 0L
                if (attrId != 0L) {
                    val resources = apkInfo.resourceTable.getResourcesById(attrId)
                    if (resources.isNotEmpty()) {
                        for (res in resources) {
                            val value = res.resourceEntry.toStringValue(apkInfo.resourceTable, locale)
                            if (value != null && (value.startsWith("#") || value.startsWith("res/"))) {
                                android.util.Log.d("AppLog", "icon fetching: resolved attr $path to $value")
                                if (value.startsWith("#")) return ColorDrawable(Color.parseColor(value))
                                
                                filterGenerator.generateZipFilter().use { filter ->
                                    val subBytes = filter.getByteArrayForEntries(emptySet(), hashSetOf(value))?.get(value)
                                    return fetchDrawable(context, value, subBytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("AppLog", "icon fetching: failed to resolve attr $path: ${e.message}")
            }
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
                        // Try to fetch from system if it's a system resource
                        val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(context.resources, resId, null)
                        if (drawable != null) {
                            android.util.Log.d("AppLog", "icon fetching: successfully fetched system resource $path")
                            return drawable
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("AppLog", "icon fetching: failed to get system resource $path: ${e.message}")
                    }
                } else {
                    // Try to resolve from app resources
                    try {
                        val resources = apkInfo.resourceTable.getResourcesById(resId.toLong())
                        if (resources.isNotEmpty()) {
                            for (res in resources) {
                                val value = res.resourceEntry.toStringValue(apkInfo.resourceTable, locale)
                                if (value != null && value != path) {
                                    android.util.Log.d("AppLog", "icon fetching: resolved resourceId $path to $value")
                                    if (value.startsWith("#")) return ColorDrawable(Color.parseColor(value))
                                    filterGenerator.generateZipFilter().use { filter ->
                                        val subBytes = if (isZipPath(value)) filter.getByteArrayForEntries(emptySet(), hashSetOf(value))?.get(value) else null
                                        return fetchDrawable(context, value, subBytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("AppLog", "icon fetching: failed to resolve app resource $path: ${e.message}")
                    }
                }
            }
            return null
        }
        if (bytes == null) {
            android.util.Log.d("AppLog", "icon fetching: bytes is null for $path")
            return null
        }
        if (!path.endsWith(".xml", true)) {
            return getAppIconFromByteArray(bytes, requestedAppIconSize, path)?.let {
                BitmapDrawable(context.resources, it)
            }
        }

        // Handle XML
        try {
            val adaptiveIconParser = AdaptiveIconParser()
            val buffer = ByteBuffer.wrap(bytes)
            val binaryXmlParser = BinaryXmlParser(buffer, apkInfo.resourceTable, adaptiveIconParser, locale)
            binaryXmlParser.parse()
            val rootTag = adaptiveIconParser.rootTag

            if (rootTag == "adaptive-icon" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val backgroundPaths = adaptiveIconParser.backgroundDrawables
                var foregroundPaths = adaptiveIconParser.foregroundDrawables
                val monochromePaths = adaptiveIconParser.monochromeDrawables
                if (foregroundPaths.isEmpty() && !monochromePaths.isEmpty()) {
                    android.util.Log.d("AppLog", "icon fetching: foreground missing, using monochrome as fallback: $monochromePaths")
                    foregroundPaths = monochromePaths
                }
                
                android.util.Log.d("AppLog", "icon fetching: adaptive-icon backgrounds: $backgroundPaths, foregrounds: $foregroundPaths, hasInline: ${adaptiveIconParser.hasInlineContent()}")
                
                if (adaptiveIconParser.hasInlineContent()) {
                    android.util.Log.d("AppLog", "icon fetching: adaptive-icon has inlined layers, using XmlDrawableParser")
                    return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, locale) { subPath ->
                        filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                    }
                }

                if (foregroundPaths.isNotEmpty()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = hashSetOf<String>()
                        pathsToFetch.addAll(backgroundPaths.filter { isZipPath(it) })
                        pathsToFetch.addAll(foregroundPaths.filter { isZipPath(it) })
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(emptySet(), pathsToFetch) ?: emptyMap() else emptyMap()
                        android.util.Log.d("AppLog", "icon fetching: retrieved ${byteArrayForEntries.size} entries for adaptive icon layers")

                        val backgroundDrawables = backgroundPaths.mapNotNull { path ->
                            fetchDrawable(context, path, byteArrayForEntries[path], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        }
                        
                        val foregroundDrawables = foregroundPaths.mapNotNull { path ->
                            fetchDrawable(context, path, byteArrayForEntries[path], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        }
                        
                        if (foregroundDrawables.isNotEmpty()) {
                            val bg = when {
                                backgroundDrawables.size > 1 -> LayerDrawable(backgroundDrawables.toTypedArray())
                                backgroundDrawables.size == 1 -> backgroundDrawables[0]
                                else -> ColorDrawable(Color.TRANSPARENT)
                            }
                            val fg = if (foregroundDrawables.size > 1) LayerDrawable(foregroundDrawables.toTypedArray()) else foregroundDrawables[0]
                            return AdaptiveIconDrawable(bg, fg)
                        } else {
                            android.util.Log.d("AppLog", "icon fetching: failed to fetch any foreground layers for adaptive icon")
                        }
                    }
                }
                // Fallback if foregroundPaths is empty or fetching failed
                android.util.Log.d("AppLog", "icon fetching: adaptive-icon manual fetch failed, fallback to XmlDrawableParser")
                return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, locale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
            } else if (rootTag == "layer-list") {
                val drawablesPaths = adaptiveIconParser.drawables
                android.util.Log.d("AppLog", "icon fetching: layer-list count: ${drawablesPaths.size}")
                if (drawablesPaths.isNotEmpty()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = drawablesPaths.filter { isZipPath(it) }.toHashSet()
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(emptySet(), pathsToFetch) ?: emptyMap() else emptyMap()
                        val drawables = drawablesPaths.mapNotNull { layerPath ->
                            fetchDrawable(context, layerPath, byteArrayForEntries[layerPath], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        }
                        if (drawables.isNotEmpty()) {
                            return LayerDrawable(drawables.toTypedArray())
                        }
                    }
                }
                android.util.Log.d("AppLog", "icon fetching: layer-list fallback to XmlDrawableParser")
                return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, locale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
            } else if (rootTag == "bitmap" || rootTag == "nine-patch" || rootTag == "inset" || rootTag == "clip" || rootTag == "scale" || rootTag == "rotate") {
                val innerPath = adaptiveIconParser.drawables.firstOrNull()
                android.util.Log.d("AppLog", "icon fetching: rootTag $rootTag, innerPath: $innerPath")
                if (!innerPath.isNullOrBlank()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val srcBytes = if (isZipPath(innerPath)) filter.getByteArrayForEntries(hashSetOf(innerPath))?.get(innerPath) else null
                        return fetchDrawable(context, innerPath, srcBytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                    }
                } else {
                    android.util.Log.d("AppLog", "icon fetching: fallback to XmlDrawableParser for $rootTag with no innerPath")
                    return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, locale) { subPath ->
                        filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                    }
                }
            } else {
                android.util.Log.d("AppLog", "icon fetching: fallback to XmlDrawableParser for rootTag: $rootTag")
                val drawable = XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, locale) { subPath ->
                    filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }
                }
                if (drawable == null) {
                    try {
                        val xmlTranslator = XmlTranslator()
                        val fallbackBuffer = ByteBuffer.wrap(bytes)
                        val fallbackBinaryXmlParser = BinaryXmlParser(fallbackBuffer, apkInfo.resourceTable, xmlTranslator, locale)
                        fallbackBinaryXmlParser.parse()
                        val xml = xmlTranslator.xml
                        android.util.Log.d("AppLog", "icon fetching: FAILED parsing XML $path. Content:\n$xml")
                        return XmlDrawableParser.tryParseDrawable(context, xml)
                    } catch (e: Exception) {
                        android.util.Log.d("AppLog", "icon fetching: failed to log XML content: ${e.message}")
                    }
                }
                return drawable
            }
        } catch (e: Exception) {
            android.util.Log.d("AppLog", "icon fetching: exception parsing XML $path: ${e.message}")
        }
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
        
        // Fallback for weird formats (like some BMPs) on API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, info, src ->
                    if (requestedAppIconSize > 0) {
                        decoder.setTargetSize(requestedAppIconSize, requestedAppIconSize)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                android.util.Log.d("AppLog", "icon fetching: ImageDecoder failed: ${e.message}")
            }
        }

        // Diagnostic hex log for failing decode
        val hex = bytes.take(32).joinToString("") { "%02x".format(it) }
        android.util.Log.e("AppLog", "icon fetching: CRITICAL: failed to decode image bytes. path: $path, size: ${bytes.size}, hex(32): $hex")
        if (bytes.size > 3 && bytes[0] == 'Q'.code.toByte() && bytes[1] == 'M'.code.toByte() && bytes[2] == 'G'.code.toByte()) {
            android.util.Log.e("AppLog", "icon fetching: IDENTIFIED Samsung QMG format. This requires proprietary Samsung decoders.")
        }

        return null
    }
}
