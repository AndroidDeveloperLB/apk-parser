package com.lb.apkparserdemo.apk_info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import com.lb.apkparserdemo.utils.AppInfoUtil
import com.lb.apkparserdemo.utils.BitmapHelper
import net.dongliu.apk.parser.bean.DeviceConfig
import net.dongliu.apk.parser.bean.IconPath
import net.dongliu.apk.parser.struct.resource.Densities
import java.nio.ByteBuffer

/**
 * Specialized utility for fetching and decoding app icons from APK files.
 * Handles various icon types including raster images (PNG/JPG), Adaptive Icons,
 * Vector Drawables, and Color Drawables.
 */
object ApkIconFetcher {
    private const val MAX_CACHE_ENTRY_SIZE = 2 * 1024 * 1024 // 2MB limit for caching entry bytes

    /** Interface to create a new [AbstractZipFilter] instance when needed during icon resolution. */
    fun interface ZipFilterCreator {
        /** Generates a fresh [AbstractZipFilter] for reading APK entries. */
        fun generateZipFilter(): AbstractZipFilter
    }

    /**
     * Attempts to retrieve the app icon from the provided APK information.
     *
     * @param context Android context.
     * @param deviceConfig Configuration for the device.
     * @param filterGenerator Generator to create new zip filters if additional entries need to be read.
     * @param apkInfo Parsed APK information.
     * @param requestedAppIconSize The desired size of the resulting icon. If 0, uses system default.
     * @return A [Bitmap] of the app icon, or null if it couldn't be found or decoded.
     */
    fun getApkIcon(
            context: Context,
            deviceConfig: DeviceConfig?,
            filterGenerator: ZipFilterCreator,
            apkInfo: ApkInfo,
            requestedAppIconSize: Int = 0
    ): Bitmap? {
        val apkMeta = apkInfo.apkMetaTranslator.apkMeta
        val iconPaths = apkInfo.apkMetaTranslator.iconPaths
        if (iconPaths.isEmpty()) {
//            android.util.Log.d("AppLog", "icon fetching: no icon paths found in manifest for ${apkMeta.packageName}")
            return null
        }

        val densityDpi = context.resources.displayMetrics.densityDpi
//        android.util.Log.d("AppLog", "icon fetching for ${apkMeta.packageName}: target densityDpi: $densityDpi, found ${iconPaths.size} icon paths")

        val sortedIconPaths = iconPaths.sortedWith(Comparator { o1: IconPath, o2: IconPath ->
            if (o1.density == o2.density) {
                val isActivity1 = o1.attrName != null && o1.attrName!!.contains("activity")
                val isActivity2 = o2.attrName != null && o2.attrName!!.contains("activity")
                if (isActivity1 != isActivity2) return@Comparator if (isActivity1) -1 else 1
                return@Comparator 0
            }

            if (o1.density == Densities.ANY) return@Comparator -1
            if (o2.density == Densities.ANY) return@Comparator 1

            val d1 = o1.density
            val d2 = o2.density
            val isNone1 = d1 == Densities.NONE || d1 == Densities.DEFAULT
            val isNone2 = d2 == Densities.NONE || d2 == Densities.DEFAULT

            if (isNone1 != isNone2) return@Comparator if (isNone1) 1 else -1

            val isHigher1 = d1 >= densityDpi
            val isHigher2 = d2 >= densityDpi

            if (isHigher1 != isHigher2) {
                return@Comparator if (isHigher1) -1 else 1
            }

            if (isHigher1) {
                return@Comparator d1.compareTo(d2)
            } else {
                return@Comparator d2.compareTo(d1)
            }
        })

        val colorIconsPaths = sortedIconPaths.mapNotNull { it.path }.filter { it.startsWith("#") }.distinct()
        val otherIconPaths = sortedIconPaths.mapNotNull { it.path }.filter { !it.startsWith("#") }.distinct()

        var bestDrawable: Drawable? = null
        var bestPath: String? = null

        val bytesCache = HashMap<String, ByteArray>()
        filterGenerator.generateZipFilter().use { filter ->
            // Try to fetch all potential icon paths in one pass
            val allBytes = filter.getByteArrayForEntries(emptySet(), otherIconPaths.toSet())
            if (allBytes != null) {
                for ((path, bytes) in allBytes) {
                    if (bytes.size <= MAX_CACHE_ENTRY_SIZE) bytesCache[path] = bytes
                }
            }
//            else {
//                android.util.Log.d("AppLog", "icon fetching: filter.getByteArrayForEntries returned null for ${apkMeta.packageName}")
//            }
        }

        for (path in otherIconPaths) {
            val bytes = bytesCache[path]
            if (bytes != null) {
                try {
                    val drawable = fetchDrawable(context, path, bytes, apkInfo, deviceConfig, filterGenerator, requestedAppIconSize, bytesCache)
                    if (drawable != null) {
                        if (bestDrawable == null || isBetterDrawable(drawable, bestDrawable!!)) {
                            bestDrawable = drawable
                            bestPath = path
                        }
                    }
//                    else {
//                        android.util.Log.d("AppLog", "icon fetching: fetchDrawable returned null for $path in ${apkMeta.packageName}")
//                    }
                } catch (e: Throwable) {
//                    android.util.Log.d("AppLog", "icon fetching: exception decoding $path: ${e.message} in ${apkMeta.packageName}")
                }
            }
//            else {
//                android.util.Log.d("AppLog", "icon fetching: path $path not found in bytesCache for ${apkMeta.packageName}")
//            }
        }

        if (bestDrawable != null) {
            val typeStr = getDetailedDrawableType(bestDrawable!!, bestPath, apkMeta.packageName)
//            android.util.Log.d("AppLog", "icon fetching for ${apkMeta.packageName}: SUCCESS: $bestPath, type: $typeStr")
            val size = if (requestedAppIconSize > 0) requestedAppIconSize else AppInfoUtil.getAppIconSize(context)
            return bestDrawable!!.toBitmap(size, size)
        }

        for (colorPath in colorIconsPaths) {
            try {
                val color = colorPath.toColorInt()
                val size = if (requestedAppIconSize > 0) requestedAppIconSize else AppInfoUtil.getAppIconSize(context)
                val bitmap = createBitmap(size, size)
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
//                android.util.Log.d("AppLog", "icon fetching for ${apkMeta.packageName}: SUCCESS with Color: $colorPath")
                return bitmap
            } catch (e: Exception) {
//                android.util.Log.d("AppLog", "icon fetching: exception for colorPath $colorPath: ${e.message} in ${apkMeta.packageName}")
            }
        }
//        android.util.Log.d("AppLog", "icon fetching for ${apkMeta.packageName}: FAILED to find any icon")
        return null
    }

    private fun isBetterDrawable(candidate: Drawable, current: Drawable): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isCandAdaptive = candidate is AdaptiveIconDrawable
            val isCurrAdaptive = current is AdaptiveIconDrawable
            if (isCandAdaptive && !isCurrAdaptive) return true
            if (isCurrAdaptive && !isCandAdaptive) return false
        }
        if (candidate is XmlDrawableParser.VectorBitmapDrawable && current !is XmlDrawableParser.VectorBitmapDrawable) {
            return true
        }
        return false
    }

    private fun getDetailedDrawableType(drawable: Drawable, path: String?, packageName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            val bg = drawable.background
            val fg = drawable.foreground
            val bgType = getDrawableType(bg)
            val fgType = getDrawableType(fg)
            val typeStr = "Adaptive icon (BG: $bgType, FG: $fgType)"
//            if (bgType == "Color" && fgType == "Color") {
//                android.util.Log.w("AppLog", "Warning: $typeStr detected for $packageName. Both layers are ColorDrawable, which is unusual.")
//            }
            typeStr
        } else if (drawable is XmlDrawableParser.VectorBitmapDrawable) {
            "VectorDrawable (Rendered)"
        } else if (path?.endsWith(".xml") == true) {
            "XML Drawable (${drawable.javaClass.simpleName})"
        } else if (drawable is BitmapDrawable) {
            "Simple raster image"
        } else {
            "Single Drawable (${drawable.javaClass.simpleName})"
        }
    }

    private fun getDrawableType(drawable: Drawable?): String {
        if (drawable == null) return "null"
        if (drawable is XmlDrawableParser.VectorBitmapDrawable) return "VectorDrawable"
        if (drawable is LayerDrawable) return "LayerList"
        if (drawable is ColorDrawable) return "Color"
        if (drawable is BitmapDrawable) return "Raster"
        return drawable.javaClass.simpleName
    }

    private fun fetchDrawable(
            context: Context,
            path: String,
            bytes: ByteArray?,
            apkInfo: ApkInfo,
            deviceConfig: DeviceConfig?,
            filterGenerator: ZipFilterCreator,
            requestedAppIconSize: Int,
            bytesCache: MutableMap<String, ByteArray> = HashMap()
    ): Drawable? {
        if (path.startsWith("#")) {
            return try {
                path.toColorInt().toDrawable()
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
                            val value = res.resourceEntry.toStringValue(apkInfo.resourceTable, deviceConfig)
                            if (value != null && (value.startsWith("#") || value.startsWith("res/"))) {
                                if (value.startsWith("#")) return value.toColorInt().toDrawable()

                                val subBytes = bytesCache[value]
                                        ?: filterGenerator.generateZipFilter().use { filter ->
                                            filter.getByteArrayForEntries(emptySet(), hashSetOf(value))?.get(value)
                                        }?.also { if (it.size <= MAX_CACHE_ENTRY_SIZE) bytesCache[value] = it }
                                return fetchDrawable(context, value, subBytes, apkInfo, deviceConfig, filterGenerator, requestedAppIconSize, bytesCache)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
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
                        val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
                        if (drawable != null) return drawable
                    } catch (e: Exception) {
                    }
                } else {
                    try {
                        val resources = apkInfo.resourceTable.getResourcesById(resId.toLong())
                        if (resources.isNotEmpty()) {
                            for (res in resources) {
                                val value = res.resourceEntry.toStringValue(apkInfo.resourceTable, deviceConfig)
                                if (value != null && value != path) {
                                    if (value.startsWith("#")) return value.toColorInt().toDrawable()
                                    val subBytes = if (isZipPath(value)) {
                                        bytesCache[value]
                                                ?: filterGenerator.generateZipFilter().use { filter ->
                                                    filter.getByteArrayForEntries(emptySet(), hashSetOf(value))?.get(value)
                                                }?.also { if (it.size <= MAX_CACHE_ENTRY_SIZE) bytesCache[value] = it }
                                    } else null
                                    return fetchDrawable(context, value, subBytes, apkInfo, deviceConfig, filterGenerator, requestedAppIconSize, bytesCache)
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            return null
        }

        if (bytes == null) return null

        if (!path.endsWith(".xml", true)) {
            return getAppIconFromByteArray(bytes, requestedAppIconSize, path)?.toDrawable(context.resources)
        }

        return XmlDrawableParser.tryParseDrawable(context, bytes, apkInfo, deviceConfig, requestedAppIconSize) { subPath ->
            bytesCache[subPath]
                    ?: filterGenerator.generateZipFilter().use { it.getByteArrayForEntries(emptySet(), hashSetOf(subPath))?.get(subPath) }?.also { if (it.size <= MAX_CACHE_ENTRY_SIZE) bytesCache[subPath] = it }
        }
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
            } catch (e: Exception) {
            }
        }
        return null
    }
}
