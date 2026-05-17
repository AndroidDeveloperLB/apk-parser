package com.lb.apkparserdemo.apk_info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import net.dongliu.apk.parser.bean.DeviceConfig
import net.dongliu.apk.parser.parser.BinaryXmlParser
import net.dongliu.apk.parser.parser.XmlStreamer
import net.dongliu.apk.parser.struct.ResourceValue
import net.dongliu.apk.parser.struct.xml.Attributes
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.*

/**
 * A complex parser that attempts to reconstruct Android Drawables from binary XML.
 * It supports VectorDrawables (rendered via Jetpack Compose), Adaptive Icons,
 * LayerLists, Insets, Rotations, and Shape Drawables with Gradients.
 */
object XmlDrawableParser {

    /**
     * A specialized [BitmapDrawable] that holds a rendered vector image.
     */
    class VectorBitmapDrawable(context: Context, bitmap: Bitmap) : BitmapDrawable(context.resources, bitmap)

    /**
     * Attempts to parse a binary XML byte array into a [Drawable].
     *
     * @param context Android context.
     * @param binXml The binary XML content of the drawable.
     * @param apkInfo Parsed APK info for resource resolution.
     * @param deviceConfig Device configuration for resource tailoring.
     * @param requestedAppIconSize Target size for rendering the drawable.
     * @param isLayer Whether this drawable is being parsed as a layer of another drawable.
     * @param subResourceProvider Callback to provide bytes for referenced sub-resources (e.g., in layer-lists).
     * @return A [Drawable] if parsing and rendering were successful, null otherwise.
     */
    fun tryParseDrawable(
            context: Context,
            binXml: ByteArray,
            apkInfo: ApkInfo,
            deviceConfig: DeviceConfig?,
            requestedAppIconSize: Int = 0,
            isLayer: Boolean = false,
            subResourceProvider: ((String) -> ByteArray?)? = null
    ): Drawable? {
        if (binXml.size < 4) return null
        val buffer = ByteBuffer.wrap(binXml).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.short.toInt() != 0x0003) return null

//        android.util.Log.d("AppLogXML", "tryParseDrawable size=${binXml.size}, requestedSize=$requestedAppIconSize, isLayer=$isLayer")
        val streamer = DrawableStreamer(context, apkInfo, deviceConfig, requestedAppIconSize, isLayer, subResourceProvider)
        val parser = BinaryXmlParser(ByteBuffer.wrap(binXml), apkInfo.resourceTable, streamer, deviceConfig)
        return try {
            parser.parse()
            streamer.result
        } catch (_: Throwable) {
            null
        }
    }

    private class DrawableStreamer(
            private val context: Context,
            private val apkInfo: ApkInfo,
            private val deviceConfig: DeviceConfig?,
            private val requestedAppIconSize: Int,
            private var isLayer: Boolean,
            private val subResourceProvider: ((String) -> ByteArray?)?
    ) : XmlStreamer {
        var result: Drawable? = null

        private val drawableStack = Stack<Any>()
        private var vectorBuilder: ImageVector.Builder? = null
        private var vectorAlpha: Float = 1f
        private val extraGroupsStack = mutableListOf<Int>()
        private var depth = 0
        private var isInsideAdaptiveLayer = false

        private fun Attributes.getAttr(name: String): String? {
            val attr = this[name] ?: this["android:$name"]
            if (attr != null) {
                val valStr = attr.toStringValue(apkInfo.resourceTable, deviceConfig)
                if (valStr is String) return valStr
                return attr.value
            }
            return null
        }

        private fun Attributes.getRawValue(name: String): Int? {
            val attr = this[name] ?: this["android:$name"]
            // Accessed via public field in modified ResourceValue class
            return attr?.typedValue?.value
        }

        private fun Attributes.getFillType(name: String): PathFillType {
            val attr = this[name] ?: this["android:$name"]
            if (attr != null) {
                val raw = getRawValue(name)
                if (raw == 1) return PathFillType.EvenOdd
                if (raw == 0) return PathFillType.NonZero

                val str = attr.toStringValue(apkInfo.resourceTable, deviceConfig)
                if (str == "evenOdd" || str == "1") return PathFillType.EvenOdd
            }
            return PathFillType.NonZero
        }

        private fun parseGravity(gravityStr: String?): Int {
            if (gravityStr == null) return -1
            var gravity = 0
            val parts = gravityStr.split('|')
            for (part in parts) {
                when (part.trim()) {
                    "center" -> gravity = gravity or android.view.Gravity.CENTER
                    "center_vertical" -> gravity = gravity or android.view.Gravity.CENTER_VERTICAL
                    "center_horizontal" -> gravity = gravity or android.view.Gravity.CENTER_HORIZONTAL
                    "fill" -> gravity = gravity or android.view.Gravity.FILL
                    "top" -> gravity = gravity or android.view.Gravity.TOP
                    "bottom" -> gravity = gravity or android.view.Gravity.BOTTOM
                    "left" -> gravity = gravity or android.view.Gravity.LEFT
                    "right" -> gravity = gravity or android.view.Gravity.RIGHT
                    "start" -> gravity = gravity or android.view.Gravity.START
                    "end" -> gravity = gravity or android.view.Gravity.END
                }
            }
            return if (gravity == 0) -1 else gravity
        }

        override fun onStartTag(tag: XmlNodeStartTag) {
            val attr = tag.attributes
            val name = tag.name

//            val logSb = StringBuilder()
//            repeat(depth) { logSb.append("  ") }
//            logSb.append("<$name")
//            for (a in attr.attributes) {
//                if (a != null) {
//                    val valStr = attr.getAttr(a.name) ?: a.value
//                    logSb.append(" ${a.name}=\"$valStr\"")
//                }
//            }
//            logSb.append(">")
//            if (depth == 0) {
//                android.util.Log.d("AppLog", "icon fetching: XML root tag: $name")
//            }
//            android.util.Log.d("AppLogXML", logSb.toString())

            when (name) {
                "vector" -> {
                    val width = attr.getAttr("width")?.parseDimension() ?: 24f
                    val height = attr.getAttr("height")?.parseDimension() ?: 24f
                    val viewportWidth = attr.getAttr("viewportWidth")?.toFloat() ?: width
                    val viewportHeight = attr.getAttr("viewportHeight")?.toFloat() ?: height
                    vectorAlpha = attr.getAttr("alpha")?.toFloat() ?: 1f

                    val newBuilder = ImageVector.Builder(
                            name = attr.getAttr("name") ?: "vector",
                            defaultWidth = width.dp,
                            defaultHeight = height.dp,
                            viewportWidth = viewportWidth,
                            viewportHeight = viewportHeight,
                            tintColor = attr.getAttr("tint")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                                    ?: Color.Unspecified,
                            tintBlendMode = parseBlendMode(attr.getAttr("tintMode")),
                            autoMirror = attr.getAttr("autoMirrored")?.toBoolean() ?: false
                    )

                    if (vectorBuilder != null) {
                        drawableStack.push(vectorBuilder!!)
                    }
                    vectorBuilder = newBuilder
                    extraGroupsStack.add(0)
                }

                "group" -> {
                    vectorBuilder?.addGroup(
                            name = attr.getAttr("name") ?: "",
                            rotate = attr.getAttr("rotation")?.toFloat() ?: 0f,
                            pivotX = attr.getAttr("pivotX")?.toFloat() ?: 0f,
                            pivotY = attr.getAttr("pivotY")?.toFloat() ?: 0f,
                            scaleX = attr.getAttr("scaleX")?.toFloat() ?: 1f,
                            scaleY = attr.getAttr("scaleY")?.toFloat() ?: 1f,
                            translationX = attr.getAttr("translateX")?.toFloat() ?: 0f,
                            translationY = attr.getAttr("translateY")?.toFloat() ?: 0f
                    )
                    extraGroupsStack.add(0)
                }

                "path" -> {
                    val pathData = attr.getAttr("pathData") ?: return
                    val fillBrush = attr.getAttr("fillColor")?.let { obtainBrush(context, it, apkInfo, deviceConfig, subResourceProvider) }
                    val strokeBrush = attr.getAttr("strokeColor")?.let { obtainBrush(context, it, apkInfo, deviceConfig, subResourceProvider) }

                    val finalFill = fillBrush
                            ?: if (attr.getAttr("fillColor") != null) SolidColor(Color.Black) else null

                    vectorBuilder?.addPath(
                            pathData = addPathNodes(pathData),
                            name = attr.getAttr("name") ?: "",
                            fill = finalFill,
                            fillAlpha = attr.getAttr("fillAlpha")?.toFloat() ?: 1f,
                            stroke = strokeBrush,
                            strokeAlpha = attr.getAttr("strokeAlpha")?.toFloat() ?: 1f,
                            strokeLineWidth = attr.getAttr("strokeWidth")?.toFloat() ?: 0f,
                            strokeLineCap = parseStrokeCap(attr.getAttr("strokeLineCap")),
                            strokeLineJoin = parseStrokeJoin(attr.getAttr("strokeLineJoin")),
                            strokeLineMiter = attr.getAttr("strokeMiterLimit")?.toFloat() ?: 4f,
                            pathFillType = attr.getFillType("fillType")
                    )
                }

                "clip-path" -> {
                    val pathData = attr.getAttr("pathData") ?: return
                    vectorBuilder?.addGroup(
                            name = attr.getAttr("name") ?: "clip",
                            clipPathData = addPathNodes(pathData)
                    )
                    if (extraGroupsStack.isNotEmpty()) {
                        extraGroupsStack[extraGroupsStack.size - 1]++
                    }
                }

                "adaptive-icon" -> {
                    drawableStack.push(AdaptiveIconBuilder())
                }

                "bitmap" -> {
                    val src = attr.getAttr("src")
                    if (src != null) {
                        val drawable = resolve(src, isLayer || isInsideAdaptiveLayer)
                        if (drawable != null) handleFinishedDrawable(drawable)
                    }
                }

                "shape" -> {
                    drawableStack.push(ShapeBuilder())
                }

                "solid" -> {
                    val builder = (if (drawableStack.isNotEmpty()) drawableStack.peek() else null) as? ShapeBuilder
                    builder?.color = attr.getAttr("color")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                }

                "gradient" -> {
                    val parent = if (drawableStack.isNotEmpty()) drawableStack.peek() else null
                    if (parent is ShapeBuilder || parent is GradientStreamer) {
                        val gradientStreamer = GradientStreamer(context, apkInfo, deviceConfig, subResourceProvider)
                        gradientStreamer.onStartTag(tag)
                        drawableStack.push(gradientStreamer)
                    }
                }

                "background", "foreground", "monochrome" -> {
                    isInsideAdaptiveLayer = true
                    val builder = (if (drawableStack.isNotEmpty()) drawableStack.peek() else null) as? AdaptiveIconBuilder
                    builder?.currentSection = name
                    attr.getAttr("drawable")?.let { builder?.setDrawable(it) }
                }

                "layer-list" -> {
                    drawableStack.push(mutableListOf<LayerItem>())
                }

                "item" -> {
                    @Suppress("UNCHECKED_CAST")
                    val layerList = (if (drawableStack.isNotEmpty()) drawableStack.peek() else null) as? MutableList<LayerItem>
                    val drawablePath = attr.getAttr("drawable")
                    val drawable = if (drawablePath != null) resolve(drawablePath, isLayer || isInsideAdaptiveLayer) else null
                    val item = LayerItem(drawable)
                    val baseSize = if (requestedAppIconSize > 0) (requestedAppIconSize * (108.0 / 72.0)).toInt() else 108
                    item.width = attr.getAttr("width")?.parseInset(baseSize) ?: -1
                    item.height = attr.getAttr("height")?.parseInset(baseSize) ?: -1
                    item.gravity = parseGravity(attr.getAttr("gravity"))
                    item.left = attr.getAttr("left")?.parseInset(baseSize) ?: 0
                    item.top = attr.getAttr("top")?.parseInset(baseSize) ?: 0
                    item.right = attr.getAttr("right")?.parseInset(baseSize) ?: 0
                    item.bottom = attr.getAttr("bottom")?.parseInset(baseSize) ?: 0
                    layerList?.add(item)
                }

                "inset" -> {
                    val drawablePath = attr.getAttr("drawable")
                    val drawable = if (drawablePath != null) resolve(drawablePath, isLayer || isInsideAdaptiveLayer) else null
                    val insetBuilder = InsetBuilder(drawable)
                    insetBuilder.insetLeft = attr.getAttr("insetLeft") ?: attr.getAttr("inset")
                    insetBuilder.insetTop = attr.getAttr("insetTop") ?: attr.getAttr("inset")
                    insetBuilder.insetRight = attr.getAttr("insetRight") ?: attr.getAttr("inset")
                    insetBuilder.insetBottom = attr.getAttr("insetBottom") ?: attr.getAttr("inset")
                    drawableStack.push(insetBuilder)
                }

                "rotate" -> {
                    val drawablePath = attr.getAttr("drawable")
                    val drawable = if (drawablePath != null) resolve(drawablePath, isLayer || isInsideAdaptiveLayer) else null
                    val rotateBuilder = RotateBuilder(drawable)
                    rotateBuilder.fromDegrees = attr.getAttr("fromDegrees")?.toFloat() ?: 0f
                    rotateBuilder.toDegrees = attr.getAttr("toDegrees")?.toFloat() ?: 360f
                    rotateBuilder.pivotX = attr.getAttr("pivotX") ?: "50%"
                    rotateBuilder.pivotY = attr.getAttr("pivotY") ?: "50%"
                    drawableStack.push(rotateBuilder)
                }

                "color" -> {
                    val color = attr.getAttr("color")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                            ?: Color.Transparent
                    if (depth == 0) result = color.toArgb().toDrawable()
                    else handleFinishedDrawable(color.toArgb().toDrawable())
                }

                "animated-vector" -> {
                    val drawablePath = attr.getAttr("drawable")
                    if (drawablePath != null) {
//                        android.util.Log.d("AppLog", "icon fetching: resolving drawable from tag <$name>: $drawablePath")
                        val drawable = resolve(drawablePath, isLayer || isInsideAdaptiveLayer)
                        if (drawable != null) handleFinishedDrawable(drawable)
                    }
                }
            }
            depth++
        }

        override fun onEndTag(tag: XmlNodeEndTag) {
            depth--
            val name = tag.name

//            val logSb = StringBuilder()
//            repeat(depth) { logSb.append("  ") }
//            logSb.append("</$name>")
//            android.util.Log.d("AppLogXML", logSb.toString())

            when (name) {
                "vector" -> {
                    if (extraGroupsStack.isNotEmpty()) {
                        val extras = extraGroupsStack.removeAt(extraGroupsStack.size - 1)
                        repeat(extras) { vectorBuilder?.clearGroup() }
                    }
                    val finishedVector = vectorBuilder?.build()
                    vectorBuilder = if (drawableStack.isNotEmpty() && drawableStack.peek() is ImageVector.Builder) {
                        drawableStack.pop() as ImageVector.Builder
                    } else null

                    if (finishedVector != null) {
                        val isLayerInVector = isLayer || isInsideAdaptiveLayer || (drawableStack.isNotEmpty() && (drawableStack.peek() is AdaptiveIconBuilder || drawableStack.peek() is MutableList<*> || drawableStack.peek() is InsetBuilder || drawableStack.peek() is RotateBuilder))
                        val drawable = imageVectorToDrawable(context, finishedVector, requestedAppIconSize, isLayerInVector, vectorAlpha)
                        handleFinishedDrawable(drawable)
                    }
                }

                "group" -> {
                    if (extraGroupsStack.isNotEmpty()) {
                        val extras = extraGroupsStack.removeAt(extraGroupsStack.size - 1)
                        repeat(extras + 1) { vectorBuilder?.clearGroup() }
                    }
                }

                "adaptive-icon" -> {
                    val builder = drawableStack.pop() as AdaptiveIconBuilder
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val bg = builder.background
                                ?: android.graphics.Color.TRANSPARENT.toDrawable()
                        val fg = builder.foreground
                                ?: android.graphics.Color.TRANSPARENT.toDrawable()

//                        if (bg is ColorDrawable && fg is ColorDrawable && (bg as ColorDrawable).color == android.graphics.Color.TRANSPARENT && (fg as ColorDrawable).color == android.graphics.Color.TRANSPARENT) {
//                            android.util.Log.w("AppLog", "Warning: Adaptive icon for ${apkInfo.apkMetaTranslator.apkMeta.packageName} has both background and foreground as transparent ColorDrawable. This is suspicious.")
//                        }

                        val drawable = AdaptiveIconDrawable(bg, fg)
                        handleFinishedDrawable(drawable)
                    }
                }

                "shape" -> {
                    val builder = drawableStack.pop() as ShapeBuilder
                    val baseSize = if (requestedAppIconSize > 0) (requestedAppIconSize * (108.0 / 72.0)).toInt() else 108
                    val drawable = if (builder.brush != null) {
                        imageBrushDrawable(context, builder.brush!!, baseSize)
                    } else if (builder.color != null) {
                        builder.color!!.toArgb().toDrawable()
                    } else {
                        android.graphics.Color.TRANSPARENT.toDrawable()
                    }
                    handleFinishedDrawable(drawable)
                }

                "gradient" -> {
                    val streamer = drawableStack.pop() as? GradientStreamer
                    streamer?.onEndTag(tag)
                    val brush = streamer?.brush
                    if (brush != null && drawableStack.isNotEmpty()) {
                        when (val parent = drawableStack.peek()) {
                            is ShapeBuilder -> parent.brush = brush
                            is GradientStreamer -> { /* Nested gradients? */
                            }
                        }
                    }
                }

                "background", "foreground", "monochrome" -> {
                    isInsideAdaptiveLayer = false
                    (drawableStack.peek() as? AdaptiveIconBuilder)?.currentSection = null
                }

                "layer-list" -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = drawableStack.pop() as MutableList<LayerItem>
                    val drawables = items.mapNotNull { it.drawable }
                    if (drawables.isNotEmpty()) {
                        val ld = LayerDrawable(drawables.toTypedArray())
                        var drawableIndex = 0
                        for (item in items) {
                            if (item.drawable != null) {
                                if (item.width != -1) ld.setLayerWidth(drawableIndex, item.width)
                                if (item.height != -1) ld.setLayerHeight(drawableIndex, item.height)
                                if (item.gravity != -1) ld.setLayerGravity(drawableIndex, item.gravity)
                                ld.setLayerInset(drawableIndex, item.left, item.top, item.right, item.bottom)
                                drawableIndex++
                            }
                        }
                        handleFinishedDrawable(ld)
                    }
                }

                "inset" -> {
                    val builder = drawableStack.pop() as InsetBuilder
                    val baseSize = if (requestedAppIconSize > 0) (requestedAppIconSize * (108.0 / 72.0)).toInt() else 108
                    val l = builder.insetLeft?.parseInset(baseSize) ?: 0
                    val t = builder.insetTop?.parseInset(baseSize) ?: 0
                    val r = builder.insetRight?.parseInset(baseSize) ?: 0
                    val b = builder.insetBottom?.parseInset(baseSize) ?: 0
                    builder.drawable?.let { handleFinishedDrawable(InsetDrawable(it, l, t, r, b)) }
                }

                "rotate" -> {
                    val builder = drawableStack.pop() as RotateBuilder
                    val rd = ManualRotateDrawable(builder.drawable)
                    rd.fromDegrees = builder.fromDegrees
                    rd.toDegrees = builder.toDegrees
                    rd.pivotX = builder.pivotX
                    rd.pivotY = builder.pivotY
                    // Default to level 0 for static previews as requested by user.
                    rd.level = 0
                    handleFinishedDrawable(rd)
                }
            }
        }

        private fun handleFinishedDrawable(drawable: Drawable) {
            if (drawableStack.isEmpty()) {
                result = drawable
            } else {
                when (val parent = drawableStack.peek()) {
                    is AdaptiveIconBuilder -> {
                        when (parent.currentSection) {
                            "background" -> parent.background = drawable
                            "foreground" -> parent.foreground = drawable
                            "monochrome" -> parent.monochrome = drawable
                        }
                    }

                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = parent as MutableList<LayerItem>
                        list.lastOrNull()?.drawable = drawable
                    }

                    is InsetBuilder -> parent.drawable = drawable
                    is RotateBuilder -> parent.drawable = drawable
                    is ManualRotateDrawable -> parent.updateInner(drawable)
                }
            }
        }

        private fun resolve(path: String, forceIsLayer: Boolean = false): Drawable? {
            if (path.startsWith("#"))
                return path.toColorInt().toDrawable()
            if (path.startsWith("resourceId:")) {
                val resId = try {
                    path.substringAfter("0x").toLong(16).toInt()
                } catch (e: Exception) {
                    0
                }
                if ((resId shr 24) == 0x01) {
                    // For system resources, use Resources.getSystem() to avoid the current app's theme/Material You pollution
                    return try {
                        val sysRes = android.content.res.Resources.getSystem()
                        androidx.core.content.res.ResourcesCompat.getDrawable(sysRes, resId, null)
                    } catch (e: Exception) {
                        try {
                            androidx.core.content.res.ResourcesCompat.getDrawable(context.resources, resId, null)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                val ref = ResourceValue.reference(resId)
                val value = ref.toStringValue(apkInfo.resourceTable, deviceConfig)
                if (value != null && value != path) return resolve(value, forceIsLayer)
            }
            val bytes = subResourceProvider?.invoke(path)
            if (bytes != null) {
                val xmlDrawable = tryParseDrawable(context, bytes, apkInfo, deviceConfig, requestedAppIconSize, forceIsLayer || isLayer, subResourceProvider)
                if (xmlDrawable != null) return xmlDrawable
                return try {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) bitmap.toDrawable(context.resources) else null
                } catch (e: Exception) {
//                    android.util.Log.e("AppLog", "icon fetching: error decoding bitmap for $path", e)
                    null
                }
            }
//            else {
//                android.util.Log.d("AppLog", "icon fetching: subResourceProvider returned null for $path")
//            }
            return null
        }

        private fun String.parseInset(totalSize: Int): Int {
//            android.util.Log.d("AppLogXML", "parseInset: value=\"$this\", totalSize=$totalSize")
            if (endsWith("%")) {
                val percentString = filter { it.isDigit() || it == '.' || it == 'E' || it == 'e' || it == '-' }
                val percent = percentString.toFloatOrNull() ?: 0f
                return (totalSize.toFloat() * percent / 100f).toInt()
            }
            val dimen = parseDimension()
            if (endsWith("dp") || endsWith("dip")) {
                return (dimen * context.resources.displayMetrics.density).toInt()
            }
            return dimen.toInt()
        }

        private inner class AdaptiveIconBuilder {
            var background: Drawable? = null
            var foreground: Drawable? = null
            var monochrome: Drawable? = null
            var currentSection: String? = null

            fun setDrawable(path: String) {
                val d = resolve(path, true)
                when (currentSection) {
                    "background" -> background = d
                    "foreground" -> foreground = d
                    "monochrome" -> monochrome = d
                }
            }
        }

        private class LayerItem(
                var drawable: Drawable? = null,
                var width: Int = -1,
                var height: Int = -1,
                var gravity: Int = -1,
                var left: Int = 0,
                var top: Int = 0,
                var right: Int = 0,
                var bottom: Int = 0
        )

        private class ShapeBuilder {
            var color: Color? = null
            var brush: Brush? = null
        }

        private class InsetBuilder(var drawable: Drawable? = null) {
            var insetLeft: String? = null
            var insetTop: String? = null
            var insetRight: String? = null
            var insetBottom: String? = null
        }

        private class RotateBuilder(var drawable: Drawable? = null) {
            var fromDegrees: Float = 0f
            var toDegrees: Float = 360f
            var pivotX: String = "50%"
            var pivotY: String = "50%"
        }

        private class ManualRotateDrawable(var inner: Drawable?) : Drawable() {
            var fromDegrees = 0f
            var toDegrees = 360f
            var pivotX = "50%"
            var pivotY = "50%"

            fun updateInner(d: Drawable) {
                inner = d
                inner?.bounds = bounds
                invalidateSelf()
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val dr = inner ?: return
                val b = bounds
                val px = parsePivot(pivotX, b.width().toFloat()) + b.left
                val py = parsePivot(pivotY, b.height().toFloat()) + b.top

                // Static previews use level 0, rotationAngle is simply fromDegrees.
                val rotationAngle = fromDegrees + (toDegrees - fromDegrees) * (level / 10000.0f)

                canvas.withRotation(rotationAngle, px, py) {
                    dr.draw(this)
                }
            }

            private fun parsePivot(p: String, size: Float): Float {
                if (p.endsWith("%")) {
                    val value = p.filter { it.isDigit() || it == '.' || it == 'E' || it == 'e' || it == '-' }.toFloatOrNull()
                            ?: 0f
                    return size * value / 100f
                }
                return p.toFloatOrNull() ?: 0f
            }

            override fun onBoundsChange(bounds: android.graphics.Rect) {
                inner?.bounds = bounds
            }

            override fun setAlpha(alpha: Int) {
                inner?.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                inner?.colorFilter = colorFilter
            }

            @Suppress("DEPRECATION")
            override fun getOpacity(): Int = inner?.opacity
                    ?: android.graphics.PixelFormat.TRANSLUCENT

            override fun onLevelChange(level: Int): Boolean {
                inner?.level = level
                invalidateSelf()
                return true
            }

            override fun getIntrinsicWidth(): Int = inner?.intrinsicWidth ?: -1
            override fun getIntrinsicHeight(): Int = inner?.intrinsicHeight ?: -1
        }

        override fun onCData(xmlCData: net.dongliu.apk.parser.struct.xml.XmlCData) {}
        override fun onNamespaceStart(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag) {}
        override fun onNamespaceEnd(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag) {}
    }

    private fun imageVectorToDrawable(context: Context, imageVector: ImageVector, requestedAppIconSize: Int = 0, isLayer: Boolean = false, alpha: Float = 1f): Drawable {
        val density = Density(context.resources.displayMetrics.density)
        val layerSizePx = if (requestedAppIconSize > 0) (requestedAppIconSize * 1.5f) else with(density) { 108.dp.toPx() }
        val widthPx = if (requestedAppIconSize > 0) (if (isLayer) layerSizePx.toInt() else requestedAppIconSize) else with(density) { imageVector.defaultWidth.toPx() }.toInt().coerceAtLeast(1)
        val heightPx = if (requestedAppIconSize > 0) (if (isLayer) layerSizePx.toInt() else requestedAppIconSize) else with(density) { imageVector.defaultHeight.toPx() }.toInt().coerceAtLeast(1)

        val bitmap = createBitmap(widthPx, heightPx)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        val drawScope = CanvasDrawScope()
        drawScope.draw(density, LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(widthPx.toFloat(), heightPx.toFloat())) {
            val colorFilter = if (imageVector.tintColor != Color.Unspecified && imageVector.tintColor != Color.Transparent) ColorFilter.tint(imageVector.tintColor, imageVector.tintBlendMode) else null
            val scaleX = widthPx.toFloat() / imageVector.viewportWidth
            val scaleY = heightPx.toFloat() / imageVector.viewportHeight
            withTransform({ scale(scaleX, scaleY, androidx.compose.ui.geometry.Offset.Zero) }) {
                if (alpha < 1f) {
                    drawIntoCanvas {
                        it.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, imageVector.viewportWidth, imageVector.viewportHeight), Paint().apply { this.alpha = alpha })
                        renderVectorGroup(imageVector.root, colorFilter)
                        it.restore()
                    }
                } else renderVectorGroup(imageVector.root, colorFilter)
            }
        }
        return VectorBitmapDrawable(context, bitmap)
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderVectorGroup(group: VectorGroup, colorFilter: ColorFilter?) {
        withTransform({
            translate(group.translationX, group.translationY)
            rotate(group.rotation, androidx.compose.ui.geometry.Offset(group.pivotX, group.pivotY))
            scale(group.scaleX, group.scaleY, androidx.compose.ui.geometry.Offset(group.pivotX, group.pivotY))
        }) {
            if (group.clipPathData.isNotEmpty()) {
                val clipPath = Path()
                addPathNodesToPath(group.clipPathData, clipPath)
                clipPath(clipPath) { group.forEach { renderVectorNode(it, colorFilter) } }
            } else group.forEach { renderVectorNode(it, colorFilter) }
        }
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderVectorNode(node: VectorNode, colorFilter: ColorFilter?) {
        when (node) {
            is VectorPath -> {
                val path = Path().apply {
                    addPathNodesToPath(node.pathData, this)
                    fillType = node.pathFillType
                }
                drawPath(path, node.fill
                        ?: SolidColor(Color.Transparent), node.fillAlpha, Fill, colorFilter)
                node.stroke?.let { drawPath(path, it, node.strokeAlpha, Stroke(width = node.strokeLineWidth, miter = node.strokeLineMiter, cap = node.strokeLineCap, join = node.strokeLineJoin), colorFilter) }
            }

            is VectorGroup -> renderVectorGroup(node, colorFilter)
        }
    }

    private fun addPathNodesToPath(nodes: List<PathNode>, path: Path) {
        var currentX = 0f
        var currentY = 0f
        var segmentX = 0f
        var segmentY = 0f
        var lastControlX = Float.NaN
        var lastControlY = Float.NaN
        for (node in nodes) {
            var nextControlX = Float.NaN
            var nextControlY = Float.NaN
            when (node) {
                is PathNode.Close -> {
                    currentX = segmentX; currentY = segmentY; path.close()
                }

                is PathNode.MoveTo -> {
                    currentX = node.x; currentY = node.y; segmentX = node.x; segmentY = node.y; path.moveTo(node.x, node.y)
                }

                is PathNode.RelativeMoveTo -> {
                    currentX += node.dx; currentY += node.dy; segmentX = currentX; segmentY = currentY; path.relativeMoveTo(node.dx, node.dy)
                }

                is PathNode.LineTo -> {
                    currentX = node.x; currentY = node.y; path.lineTo(node.x, node.y)
                }

                is PathNode.RelativeLineTo -> {
                    currentX += node.dx; currentY += node.dy; path.relativeLineTo(node.dx, node.dy)
                }

                is PathNode.HorizontalTo -> {
                    currentX = node.x; path.lineTo(node.x, currentY)
                }

                is PathNode.RelativeHorizontalTo -> {
                    currentX += node.dx; path.relativeLineTo(node.dx, 0f)
                }

                is PathNode.VerticalTo -> {
                    currentY = node.y; path.lineTo(currentX, node.y)
                }

                is PathNode.RelativeVerticalTo -> {
                    currentY += node.dy; path.relativeLineTo(0f, node.dy)
                }

                is PathNode.CurveTo -> {
                    path.cubicTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3); nextControlX = node.x2; nextControlY = node.y2; currentX = node.x3; currentY = node.y3
                }

                is PathNode.RelativeCurveTo -> {
                    path.relativeCubicTo(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3); nextControlX = currentX + node.dx2; nextControlY = currentY + node.dy2; currentX += node.dx3; currentY += node.dy3
                }

                is PathNode.QuadTo -> {
                    path.quadraticTo(node.x1, node.y1, node.x2, node.y2); nextControlX = node.x1; nextControlY = node.y1; currentX = node.x2; currentY = node.y2
                }

                is PathNode.RelativeQuadTo -> {
                    path.relativeQuadraticTo(node.dx1, node.dy1, node.dx2, node.dy2); nextControlX = currentX + node.dx1; nextControlY = currentY + node.dy1; currentX += node.dx2; currentY += node.dy2
                }

                is PathNode.ArcTo -> {
                    drawArc(path, currentX.toDouble(), currentY.toDouble(), node.arcStartX.toDouble(), node.arcStartY.toDouble(), node.horizontalEllipseRadius.toDouble(), node.verticalEllipseRadius.toDouble(), node.theta.toDouble(), node.isMoreThanHalf, node.isPositiveArc); currentX = node.arcStartX; currentY = node.arcStartY
                }

                is PathNode.RelativeArcTo -> {
                    val nextX = currentX + node.arcStartDx
                    val nextY = currentY + node.arcStartDy; drawArc(path, currentX.toDouble(), currentY.toDouble(), nextX.toDouble(), nextY.toDouble(), node.horizontalEllipseRadius.toDouble(), node.verticalEllipseRadius.toDouble(), node.theta.toDouble(), node.isMoreThanHalf, node.isPositiveArc); currentX = nextX; currentY = nextY
                }

                is PathNode.ReflectiveCurveTo -> {
                    val cx = if (lastControlX.isNaN()) currentX else 2 * currentX - lastControlX
                    val cy = if (lastControlY.isNaN()) currentY else 2 * currentY - lastControlY; path.cubicTo(cx, cy, node.x1, node.y1, node.x2, node.y2); nextControlX = node.x1; nextControlY = node.y1; currentX = node.x2; currentY = node.y2
                }

                is PathNode.RelativeReflectiveCurveTo -> {
                    val cx = if (lastControlX.isNaN()) currentX else 2 * currentX - lastControlX
                    val cy = if (lastControlY.isNaN()) currentY else 2 * currentY - lastControlY; path.cubicTo(cx, cy, currentX + node.dx1, currentY + node.dy1, currentX + node.dx2, currentY + node.dy2); nextControlX = currentX + node.dx1; nextControlY = currentY + node.dy1; currentX += node.dx2; currentY += node.dy2
                }

                is PathNode.ReflectiveQuadTo -> {
                    val cx = if (lastControlX.isNaN()) currentX else 2 * currentX - lastControlX
                    val cy = if (lastControlY.isNaN()) currentY else 2 * currentY - lastControlY; path.quadraticTo(cx, cy, node.x, node.y); nextControlX = cx; nextControlY = cy; currentX = node.x; currentY = node.y
                }

                is PathNode.RelativeReflectiveQuadTo -> {
                    val cx = if (lastControlX.isNaN()) currentX else 2 * currentX - lastControlX
                    val cy = if (lastControlY.isNaN()) currentY else 2 * currentY - lastControlY; path.quadraticTo(cx, cy, currentX + node.dx, currentY + node.dy); nextControlX = cx; nextControlY = cy; currentX += node.dx; currentY += node.dy
                }
            }
            lastControlX = nextControlX; lastControlY = nextControlY
        }
    }

    private fun drawArc(path: Path, x0: Double, y0: Double, x1: Double, y1: Double, a: Double, b: Double, theta: Double, isLargeArc: Boolean, isSweep: Boolean) {
        if (x0 == x1 && y0 == y1) return
        var rx = abs(a)
        var ry = abs(b)
        if (rx == 0.0 || ry == 0.0) {
            path.lineTo(x1.toFloat(), y1.toFloat()); return
        }
        val thetaRad = Math.toRadians(theta)
        val cosTheta = cos(thetaRad)
        val sinTheta = sin(thetaRad)
        val dx2 = (x0 - x1) / 2.0
        val dy2 = (y0 - y1) / 2.0
        val x1p = cosTheta * dx2 + sinTheta * dy2
        val y1p = -sinTheta * dx2 + cosTheta * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) {
            rx *= sqrt(lambda); ry *= sqrt(lambda)
        }
        val rxSq = rx * rx
        val rySq = ry * ry
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p
        var radicand = (rxSq * rySq - rxSq * y1pSq - rySq * x1pSq) / (rxSq * y1pSq + rySq * x1pSq)
        radicand = max(0.0, radicand)
        val coef = (if (isLargeArc == isSweep) -1.0 else 1.0) * sqrt(radicand)
        val cxp = coef * ((rx * y1p) / ry)
        val cyp = coef * (-(ry * x1p) / rx)
        val cx = cosTheta * cxp - sinTheta * cyp + (x0 + x1) / 2.0
        val cy = sinTheta * cxp + cosTheta * cyp + (y0 + y1) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            if (len == 0.0) return 0.0
            var ang = acos(max(-1.0, min(1.0, dot / len)))
            if (ux * vy - uy * vx < 0.0) ang = -ang
            return ang
        }

        val startAngle = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var deltaAngle = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!isSweep && deltaAngle > 0) deltaAngle -= 2 * PI else if (isSweep && deltaAngle < 0) deltaAngle += 2 * PI
        val numSegments = ceil(abs(deltaAngle) / (PI / 2.0)).toInt()
        var angle = startAngle
        for (i in 0 until numSegments) {
            val segmentDelta = deltaAngle / numSegments
            val bx = rx * cos(angle + segmentDelta)
            val by = ry * sin(angle + segmentDelta)
            val t = 4.0 / 3.0 * tan(segmentDelta / 4.0)
            val x2 = rx * cos(angle) - t * ry * sin(angle)
            val y2 = ry * sin(angle) + t * rx * cos(angle)
            val x3 = bx + t * rx * sin(angle + segmentDelta)
            val y3 = by - t * rx * cos(angle + segmentDelta)
            val ex = if (i == numSegments - 1) x1.toFloat() else (cosTheta * bx - sinTheta * by + cx).toFloat()
            val ey = if (i == numSegments - 1) y1.toFloat() else (sinTheta * bx + cosTheta * by + cy).toFloat()
            path.cubicTo((cosTheta * x2 - sinTheta * y2 + cx).toFloat(), (sinTheta * x2 + cosTheta * y2 + cy).toFloat(), (cosTheta * x3 - sinTheta * y3 + cx).toFloat(), (sinTheta * x3 + cosTheta * y3 + cy).toFloat(), ex, ey)
            angle += segmentDelta
        }
    }

    private fun addPathNodes(pathData: String): List<PathNode> = androidx.compose.ui.graphics.vector.addPathNodes(pathData)
    private fun String.parseDimension(): Float = filter { it.isDigit() || it == '.' || it == '-' || it == 'e' || it == 'E' }.toFloatOrNull()
            ?: 0f

    private fun obtainBrush(context: Context, colorStr: String, apkInfo: ApkInfo, deviceConfig: DeviceConfig?, subResourceProvider: ((String) -> ByteArray?)?): Brush? {
        if (colorStr.startsWith("#")) {
            val c = when (colorStr.length) {
                4 -> "#" + colorStr[1] + colorStr[1] + colorStr[2] + colorStr[2] + colorStr[3] + colorStr[3]
                5 -> "#" + colorStr[1] + colorStr[1] + colorStr[2] + colorStr[2] + colorStr[3] + colorStr[3] + colorStr[4] + colorStr[4]
                else -> colorStr
            }
            return try {
                SolidColor(Color(c.toColorInt()))
            } catch (e: Exception) {
                null
            }
        }
        if (colorStr.startsWith("resourceId:")) {
            val resId = try {
                colorStr.substringAfter("0x").toLong(16).toInt()
            } catch (e: Exception) {
                0
            }
            if ((resId shr 24) == 0x01) {
                // For system colors, use Resources.getSystem() to avoid the current app's theme/Material You pollution
                return try {
                    val sysRes = android.content.res.Resources.getSystem()
                    SolidColor(Color(sysRes.getColor(resId, null)))
                } catch (_: Exception) {
                    try {
                        SolidColor(Color(androidx.core.content.res.ResourcesCompat.getColor(context.resources, resId, null)))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val value = ResourceValue.reference(resId).toStringValue(apkInfo.resourceTable, deviceConfig)
            if (value != null && value != colorStr) return obtainBrush(context, value, apkInfo, deviceConfig, subResourceProvider)
        }
        if (colorStr.endsWith(".xml") && subResourceProvider != null) {
            val bytes = subResourceProvider(colorStr)
            if (bytes != null) {
                tryParseComplexColor(context, bytes, apkInfo, deviceConfig, subResourceProvider)?.let { return it }
                val csl = parseColorStateList(context, bytes, apkInfo, deviceConfig, subResourceProvider)
                if (csl != Color.Transparent) return SolidColor(csl)
            }
        }
        return null
    }

    private fun resolveColor(context: Context, colorStr: String, apkInfo: ApkInfo, deviceConfig: DeviceConfig?, subResourceProvider: ((String) -> ByteArray?)? = null): Color {
        val b = obtainBrush(context, colorStr, apkInfo, deviceConfig, subResourceProvider)
        return if (b is SolidColor) b.value else Color.Transparent
    }

    private fun parseColorStateList(context: Context, bytes: ByteArray, apkInfo: ApkInfo, deviceConfig: DeviceConfig?, subResourceProvider: ((String) -> ByteArray?)?): Color {
        var res = Color.Transparent
        var def = Color.Transparent
        val streamer = object : XmlStreamer {
            override fun onStartTag(tag: XmlNodeStartTag) {
                if (tag.name == "item") {
                    val colorAttr = tag.attributes["color"]
                            ?: tag.attributes["android:color"]
                    colorAttr?.toStringValue(apkInfo.resourceTable, deviceConfig)?.let {
                        val c = resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider)
                        if (res == Color.Transparent) res = c
                        if (tag.attributes.attributes.none { a -> a != null && a.name.contains("state_") }) def = c
                    }
                }
            }

            override fun onEndTag(tag: XmlNodeEndTag) {}
            override fun onCData(xmlCData: net.dongliu.apk.parser.struct.xml.XmlCData) {}
            override fun onNamespaceStart(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag) {}
            override fun onNamespaceEnd(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag) {}
        }
        try {
            BinaryXmlParser(ByteBuffer.wrap(bytes), apkInfo.resourceTable, streamer, deviceConfig).parse()
        } catch (e: Exception) {
        }
        return if (def != Color.Transparent) def else res
    }

    private fun tryParseComplexColor(context: Context, bytes: ByteArray, apkInfo: ApkInfo, deviceConfig: DeviceConfig?, subResourceProvider: ((String) -> ByteArray?)?): Brush? {
        val streamer = GradientStreamer(context, apkInfo, deviceConfig, subResourceProvider)
        try {
            BinaryXmlParser(ByteBuffer.wrap(bytes), apkInfo.resourceTable, streamer, deviceConfig).parse(); return streamer.brush
        } catch (e: Exception) {
        }
        return null
    }

    private class GradientStreamer(private val context: Context, private val apkInfo: ApkInfo, private val deviceConfig: DeviceConfig?, private val subResourceProvider: ((String) -> ByteArray?)?) : XmlStreamer {
        var brush: Brush? = null
        private var type: String? = null
        private var startColor = Color.Transparent
        private var endColor = Color.Transparent
        private var centerColor: Color? = null
        private var sx = 0f
        private var sy = 0f
        private var ex = 0f
        private var ey = 0f
        private var cx = 0f
        private var cy = 0f
        private var gr = 0f
        private var angle = 0f
        private val stops = mutableListOf<Float>()
        private val colors = mutableListOf<Color>()
        private fun Attributes.getAttr(name: String) = (this[name]
                ?: this["android:$name"])?.toStringValue(apkInfo.resourceTable, deviceConfig)
                ?: (this[name] ?: this["android:$name"])?.value

        override fun onStartTag(tag: XmlNodeStartTag) {
            val a = tag.attributes
            when (tag.name) {
                "gradient" -> {
                    type = when (a.getAttr("type")) {
                        "1", "radial" -> "radial"; "2", "sweep" -> "sweep"; else -> "linear"
                    }
                    startColor = a.getAttr("startColor")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                            ?: Color.Transparent
                    endColor = a.getAttr("endColor")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                            ?: Color.Transparent
                    centerColor = a.getAttr("centerColor")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                    sx = a.getAttr("startX")?.toFloat() ?: 0f; sy = a.getAttr("startY")?.toFloat()
                            ?: 0f
                    ex = a.getAttr("endX")?.toFloat() ?: 0f; ey = a.getAttr("endY")?.toFloat() ?: 0f
                    cx = a.getAttr("centerX")?.toFloat() ?: 0f; cy = a.getAttr("centerY")?.toFloat()
                            ?: 0f
                    gr = a.getAttr("gradientRadius")?.toFloat()
                            ?: 0f; angle = a.getAttr("angle")?.toFloat() ?: 0f
                }

                "item" -> {
                    stops.add(a.getAttr("offset")?.toFloat() ?: 0f)
                    colors.add(a.getAttr("color")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                            ?: Color.Transparent)
                }

                "color" -> brush = SolidColor(a.getAttr("color")?.let { resolveColor(context, it, apkInfo, deviceConfig, subResourceProvider) }
                        ?: Color.Transparent)
            }
        }

        override fun onEndTag(tag: XmlNodeEndTag) {
            if (tag.name == "gradient") {
                val fcolors = colors.ifEmpty {
                    (centerColor?.let { listOf(startColor, it, endColor) }
                            ?: listOf(startColor, endColor))
                }
                val fstops = stops.ifEmpty { (if (centerColor != null) listOf(0f, 0.5f, 1f) else listOf(0f, 1f)) }
                val stopsArray = fstops.zip(fcolors).toTypedArray()
                brush = when (type) {
                    "radial" -> Brush.radialGradient(colorStops = stopsArray, center = androidx.compose.ui.geometry.Offset(cx, cy), radius = gr)
                    "sweep" -> Brush.sweepGradient(colorStops = stopsArray, center = androidx.compose.ui.geometry.Offset(cx, cy))
                    else -> if (sx == 0f && sy == 0f && ex == 0f && ey == 0f && angle != 0f) RelativeLinearGradient(fstops.zip(fcolors), calculateGradientCoords(angle))
                    else Brush.linearGradient(colorStops = stopsArray, start = androidx.compose.ui.geometry.Offset(sx, sy), end = androidx.compose.ui.geometry.Offset(ex, ey))
                }
            }
        }

        private fun calculateGradientCoords(a: Float) = when ((((a % 360) + 360) % 360).toInt()) {
            0 -> floatArrayOf(0f, 0.5f, 1f, 0.5f); 45 -> floatArrayOf(0f, 1f, 1f, 0f); 90 -> floatArrayOf(0.5f, 1f, 0.5f, 0f); 135 -> floatArrayOf(1f, 1f, 0f, 0f)
            180 -> floatArrayOf(1f, 0.5f, 0f, 0.5f); 225 -> floatArrayOf(1f, 0f, 0f, 1f); 270 -> floatArrayOf(0.5f, 0f, 0.5f, 1f); 315 -> floatArrayOf(0f, 0f, 1f, 1f)
            else -> floatArrayOf(0f, 0.5f, 1f, 0.5f)
        }

        override fun onCData(xmlCData: net.dongliu.apk.parser.struct.xml.XmlCData) {}
        override fun onNamespaceStart(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag) {}
        override fun onNamespaceEnd(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag) {}
    }

    private class RelativeLinearGradient(val s: List<Pair<Float, Color>>, val c: FloatArray) : ShaderBrush() {
        override fun createShader(size: androidx.compose.ui.geometry.Size) = android.graphics.LinearGradient(c[0] * size.width, c[1] * size.height, c[2] * size.width, c[3] * size.height, s.map { it.second.toArgb() }.toIntArray(), s.map { it.first }.toFloatArray(), android.graphics.Shader.TileMode.CLAMP)
    }

    private fun imageBrushDrawable(context: Context, brush: Brush, size: Int): Drawable {
        val b = createBitmap(size, size)
        val canvas = Canvas(android.graphics.Canvas(b))
        CanvasDrawScope().draw(Density(context.resources.displayMetrics.density), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(size.toFloat(), size.toFloat())) { drawRect(brush) }
        return VectorBitmapDrawable(context, b)
    }

    private fun parseBlendMode(s: String?): BlendMode = when (s) {
        "src_over", "3" -> BlendMode.SrcOver; "src_in", "5" -> BlendMode.SrcIn; "src_atop", "9" -> BlendMode.SrcAtop; "multiply", "14" -> BlendMode.Modulate; "screen", "15" -> BlendMode.Screen; "add", "16" -> BlendMode.Plus; else -> BlendMode.SrcIn
    }

    private fun parseStrokeCap(s: String?): StrokeCap = when (s) {
        "butt", "0" -> StrokeCap.Butt; "round", "1" -> StrokeCap.Round; "square", "2" -> StrokeCap.Square; else -> StrokeCap.Butt
    }

    private fun parseStrokeJoin(s: String?): StrokeJoin = when (s) {
        "miter", "0" -> StrokeJoin.Miter; "round", "1" -> StrokeJoin.Round; "bevel", "2" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter
    }
}
