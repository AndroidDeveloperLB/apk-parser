package com.lb.apkparserdemo.apk_info.app_icon

import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
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
        if (bytes == null) {
            android.util.Log.d("AppLog", "icon fetching: bytes is null for $path")
            return null
        }
        if (!path.endsWith(".xml", true)) {
            return getAppIconFromByteArray(bytes, requestedAppIconSize)?.let {
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
                val backgroundPath = adaptiveIconParser.background
                var foregroundPath = adaptiveIconParser.foreground
                val monochromePath = adaptiveIconParser.monochrome
                if (foregroundPath.isNullOrBlank() && !monochromePath.isNullOrBlank()) {
                    android.util.Log.d("AppLog", "icon fetching: foreground missing, using monochrome as fallback: $monochromePath")
                    foregroundPath = monochromePath
                }
                
                android.util.Log.d("AppLog", "icon fetching: adaptive-icon background: $backgroundPath, foreground: $foregroundPath")
                if (!foregroundPath.isNullOrBlank()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = hashSetOf<String>()
                        if (backgroundPath != null && !backgroundPath.startsWith("#")) pathsToFetch.add(backgroundPath)
                        if (!foregroundPath.startsWith("#")) pathsToFetch.add(foregroundPath)
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(pathsToFetch) ?: emptyMap() else emptyMap()
                        android.util.Log.d("AppLog", "icon fetching: retrieved ${byteArrayForEntries.size} entries for adaptive icon layers")

                        val backgroundDrawable = if (backgroundPath != null) {
                            fetchDrawable(context, backgroundPath, byteArrayForEntries[backgroundPath], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        } else {
                            android.util.Log.d("AppLog", "icon fetching: background missing, using transparent")
                            ColorDrawable(Color.TRANSPARENT)
                        }
                        
                        val foregroundDrawable = fetchDrawable(context, foregroundPath, byteArrayForEntries[foregroundPath], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        
                        if (foregroundDrawable != null) {
                            val bg = backgroundDrawable ?: ColorDrawable(Color.TRANSPARENT)
                            return AdaptiveIconDrawable(bg, foregroundDrawable)
                        } else {
                            android.util.Log.d("AppLog", "icon fetching: failed to fetch foreground ($foregroundPath) for adaptive icon")
                        }
                    }
                }
            } else if (rootTag == "layer-list") {
                val drawablesPaths = adaptiveIconParser.drawables
                android.util.Log.d("AppLog", "icon fetching: layer-list count: ${drawablesPaths.size}")
                if (drawablesPaths.isNotEmpty()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = drawablesPaths.filter { !it.startsWith("#") }.toHashSet()
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(pathsToFetch) ?: emptyMap() else emptyMap()
                        val drawables = drawablesPaths.mapNotNull { layerPath ->
                            fetchDrawable(context, layerPath, byteArrayForEntries[layerPath], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        }
                        if (drawables.isNotEmpty()) {
                            return LayerDrawable(drawables.toTypedArray())
                        }
                    }
                }
            } else if (rootTag == "bitmap" || rootTag == "nine-patch" || rootTag == "inset" || rootTag == "clip" || rootTag == "scale" || rootTag == "rotate") {
                val innerPath = adaptiveIconParser.drawables.firstOrNull()
                android.util.Log.d("AppLog", "icon fetching: rootTag $rootTag, innerPath: $innerPath")
                if (!innerPath.isNullOrBlank()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val srcBytes = if (!innerPath.startsWith("#")) filter.getByteArrayForEntries(hashSetOf(innerPath))?.get(innerPath) else null
                        return fetchDrawable(context, innerPath, srcBytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                    }
                }
            } else {
                android.util.Log.d("AppLog", "icon fetching: fallback to XmlDrawableParser for rootTag: $rootTag")
                val drawable = XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, locale)
                if (drawable == null) {
                    try {
                        val xmlTranslator = XmlTranslator()
                        val fallbackBuffer = ByteBuffer.wrap(bytes)
                        val fallbackBinaryXmlParser = BinaryXmlParser(fallbackBuffer, apkInfo.resourceTable, xmlTranslator, locale)
                        fallbackBinaryXmlParser.parse()
                        val xml = xmlTranslator.xml
                        android.util.Log.d("AppLog", "icon fetching: XML content for $path:\n$xml")
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
