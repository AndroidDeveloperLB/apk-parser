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
        if (iconPaths.isEmpty()) return null

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
            filterGenerator.generateZipFilter().use { filter ->
                val bytes = filter.getByteArrayForEntries(hashSetOf(path))?.get(path)
                if (bytes != null) {
                    try {
                        val drawable = fetchDrawable(context, path, bytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                        if (drawable != null) {
                            return drawable.toBitmap(requestedAppIconSize, requestedAppIconSize)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }

        // Try colors if everything else failed
        for (colorPath in colorIconsPaths) {
            try {
                val color = Color.parseColor(colorPath)
                val bitmap = Bitmap.createBitmap(requestedAppIconSize, requestedAppIconSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
                return bitmap
            } catch (e: Exception) {
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
        if (bytes == null) return null
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
                val foregroundPath = adaptiveIconParser.foreground
                if (!backgroundPath.isNullOrBlank() && !foregroundPath.isNullOrBlank()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val pathsToFetch = hashSetOf<String>()
                        if (!backgroundPath.startsWith("#")) pathsToFetch.add(backgroundPath)
                        if (!foregroundPath.startsWith("#")) pathsToFetch.add(foregroundPath)
                        val byteArrayForEntries = if (pathsToFetch.isNotEmpty()) filter.getByteArrayForEntries(pathsToFetch) ?: emptyMap() else emptyMap()

                        val backgroundDrawable = fetchDrawable(context, backgroundPath, byteArrayForEntries[backgroundPath], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        val foregroundDrawable = fetchDrawable(context, foregroundPath, byteArrayForEntries[foregroundPath], apkInfo, locale, filterGenerator, requestedAppIconSize)
                        if (backgroundDrawable != null && foregroundDrawable != null) {
                            return AdaptiveIconDrawable(backgroundDrawable, foregroundDrawable)
                        }
                    }
                }
            } else if (rootTag == "layer-list") {
                val drawablesPaths = adaptiveIconParser.drawables
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
            } else if (rootTag == "bitmap" || rootTag == "nine-patch") {
                val srcPath = adaptiveIconParser.drawables.firstOrNull()
                if (!srcPath.isNullOrBlank()) {
                    filterGenerator.generateZipFilter().use { filter ->
                        val srcBytes = if (!srcPath.startsWith("#")) filter.getByteArrayForEntries(hashSetOf(srcPath))?.get(srcPath) else null
                        return fetchDrawable(context, srcPath, srcBytes, apkInfo, locale, filterGenerator, requestedAppIconSize)
                    }
                }
            } else {
                // Try framework parser as a generic fallback for any other tag (vector, selector, etc.)
                var drawable = XmlDrawableParser.tryParseDrawable(context, bytes)
                if (drawable == null) {
                    // fallback to text XML translation which resolves references
                    val xmlTranslator = XmlTranslator()
                    val fallbackBuffer = ByteBuffer.wrap(bytes)
                    val fallbackBinaryXmlParser = BinaryXmlParser(fallbackBuffer, apkInfo.resourceTable, xmlTranslator, locale)
                    fallbackBinaryXmlParser.parse()
                    val xml = xmlTranslator.xml
                    drawable = XmlDrawableParser.tryParseDrawable(context, xml)
                }
                return drawable
            }
        } catch (e: Exception) {
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
