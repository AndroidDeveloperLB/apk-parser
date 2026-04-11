package com.lb.apkparserdemo.apk_info

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.dongliu.apk.parser.parser.BinaryXmlParser
import net.dongliu.apk.parser.parser.XmlStreamer
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.nio.ByteBuffer
import java.util.*

object XmlDrawableParser {

    fun tryParseDrawable(context: Context, binXml: ByteArray, apkInfo: ApkInfo, locale: Locale, subResourceProvider: ((String) -> ByteArray?)? = null): Drawable? {
        android.util.Log.d("AppLog", "icon fetching: tryParseDrawable (Binary)")
        val streamer = VectorDrawableStreamer(context, apkInfo, locale, subResourceProvider)
        val parser = BinaryXmlParser(ByteBuffer.wrap(binXml), apkInfo.resourceTable, streamer, locale)
        return try {
            parser.parse()
            if (streamer.isVector) {
                android.util.Log.d("AppLog", "icon fetching: parsed as VectorDrawable")
                streamer.imageVector?.let { imageVectorToDrawable(context, it) }
            } else {
                // Fallback to framework for non-vector drawables (layer-list, etc.)
                android.util.Log.d("AppLog", "icon fetching: not a vector, fallback to framework")
                tryParseFrameworkDrawable(context, binXml)
            }
        } catch (e: Exception) {
            android.util.Log.d("AppLog", "icon fetching: exception in BinaryXmlParser: ${e.message}")
            // Last resort fallback
            tryParseFrameworkDrawable(context, binXml)
        }
    }

    fun tryParseDrawable(context: Context, xml: String): Drawable? {
        android.util.Log.d("AppLog", "icon fetching: tryParseDrawable (String XML)")
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))
            var type = parser.next()
            while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
                type = parser.next()
            }
            if (type != XmlPullParser.START_TAG) return null

            return if (parser.name == "vector") {
                android.util.Log.d("AppLog", "icon fetching: parsed string XML as vector")
                val imageVector = parseVectorFromPullParser(context, parser)
                imageVector?.let { imageVectorToDrawable(context, it) }
            } else {
                android.util.Log.d("AppLog", "icon fetching: parsed string XML root: ${parser.name}, fallback to framework")
                val attrs = Xml.asAttributeSet(parser)
                Drawable.createFromXmlInner(context.resources, parser, attrs, context.theme)
            }
        } catch (e: Exception) {
            android.util.Log.d("AppLog", "icon fetching: exception parsing string XML: ${e.message}")
        }
        return null
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun tryParseFrameworkDrawable(context: Context, binXml: ByteArray): Drawable? {
        try {
            val xmlBlock = Class.forName("android.content.res.XmlBlock")
            val xmlBlockCtr = xmlBlock.getConstructor(ByteArray::class.java)
            val xmlParserNew = xmlBlock.getDeclaredMethod("newParser")
            xmlBlockCtr.isAccessible = true
            xmlParserNew.isAccessible = true
            val parser = xmlParserNew.invoke(xmlBlockCtr.newInstance(binXml)) as XmlPullParser
            var type = parser.next()
            while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
                type = parser.next()
            }
            if (type == XmlPullParser.START_TAG) {
                val attrs = Xml.asAttributeSet(parser)
                return Drawable.createFromXmlInner(context.resources, parser, attrs, context.theme)
            }
        } catch (e: Exception) {
        }
        return null
    }

    private class VectorDrawableStreamer(
        private val context: Context,
        private val apkInfo: ApkInfo,
        private val locale: Locale,
        private val subResourceProvider: ((String) -> ByteArray?)?
    ) : XmlStreamer {
        var imageVector: ImageVector? = null
        var isVector = false
        private var builder: ImageVector.Builder? = null
        private val extraGroupsStack = mutableListOf<Int>()

        override fun onStartTag(tag: XmlNodeStartTag) {
            val attr = tag.attributes
            when (tag.name) {
                "vector" -> {
                    isVector = true
                    val width = attr.getString("width")?.parseDimension() ?: 24f
                    val height = attr.getString("height")?.parseDimension() ?: 24f
                    val viewportWidth = attr.getString("viewportWidth")?.toFloat() ?: width
                    val viewportHeight = attr.getString("viewportHeight")?.toFloat() ?: height
                    
                    builder = ImageVector.Builder(
                        name = attr.getString("name") ?: "vector",
                        defaultWidth = width.dp,
                        defaultHeight = height.dp,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        tintColor = attr.getString("tint")?.let { parseColor(context, it) } ?: Color.Unspecified,
                        tintBlendMode = parseBlendMode(attr.getString("tintMode")),
                        autoMirror = attr.getBoolean("autoMirrored", false)
                    )
                    extraGroupsStack.add(0)
                }
                "group" -> {
                    builder?.addGroup(
                        name = attr.getString("name") ?: "",
                        rotate = attr.getString("rotation")?.toFloat() ?: 0f,
                        pivotX = attr.getString("pivotX")?.toFloat() ?: 0f,
                        pivotY = attr.getString("pivotY")?.toFloat() ?: 0f,
                        scaleX = attr.getString("scaleX")?.toFloat() ?: 1f,
                        scaleY = attr.getString("scaleY")?.toFloat() ?: 1f,
                        translationX = attr.getString("translateX")?.toFloat() ?: 0f,
                        translationY = attr.getString("translateY")?.toFloat() ?: 0f
                    )
                    extraGroupsStack.add(0)
                }
                "path" -> {
                    val pathData = attr.getString("pathData") ?: return
                    builder?.addPath(
                        pathData = addPathNodes(pathData),
                        name = attr.getString("name") ?: "",
                        fill = attr.getString("fillColor")?.let { obtainBrush(context, it, apkInfo, locale, subResourceProvider) },
                        fillAlpha = attr.getString("fillAlpha")?.toFloat() ?: 1f,
                        stroke = attr.getString("strokeColor")?.let { obtainBrush(context, it, apkInfo, locale, subResourceProvider) },
                        strokeAlpha = attr.getString("strokeAlpha")?.toFloat() ?: 1f,
                        strokeLineWidth = attr.getString("strokeWidth")?.toFloat() ?: 0f,
                        strokeLineCap = parseStrokeCap(attr.getString("strokeLineCap")),
                        strokeLineJoin = parseStrokeJoin(attr.getString("strokeLineJoin")),
                        strokeLineMiter = attr.getString("strokeMiterLimit")?.toFloat() ?: 4f,
                        pathFillType = if (attr.getString("fillType") == "evenOdd") PathFillType.EvenOdd else PathFillType.NonZero
                    )
                }
                "clip-path" -> {
                    val pathData = attr.getString("pathData") ?: return
                    builder?.addGroup(
                        name = attr.getString("name") ?: "",
                        clipPathData = addPathNodes(pathData)
                    )
                    if (extraGroupsStack.isNotEmpty()) {
                        extraGroupsStack[extraGroupsStack.size - 1]++
                    }
                }
            }
        }

        override fun onEndTag(tag: XmlNodeEndTag) {
            when (tag.name) {
                "vector" -> {
                    if (extraGroupsStack.isNotEmpty()) {
                        val extras = extraGroupsStack.removeAt(extraGroupsStack.size - 1)
                        repeat(extras) { builder?.clearGroup() }
                    }
                    imageVector = builder?.build()
                }
                "group" -> {
                    if (extraGroupsStack.isNotEmpty()) {
                        val extras = extraGroupsStack.removeAt(extraGroupsStack.size - 1)
                        repeat(extras + 1) { builder?.clearGroup() }
                    }
                }
            }
        }

        override fun onCData(xmlCData: net.dongliu.apk.parser.struct.xml.XmlCData) {}
        override fun onNamespaceStart(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag) {}
        override fun onNamespaceEnd(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag) {}
    }

    private fun parseVectorFromPullParser(context: Context, parser: XmlPullParser): ImageVector? {
        val ns = "http://schemas.android.com/apk/res/android"
        val width = parser.getAttributeValue(ns, "width")?.parseDimension() ?: 24f
        val height = parser.getAttributeValue(ns, "height")?.parseDimension() ?: 24f
        val viewportWidth = parser.getAttributeValue(ns, "viewportWidth")?.toFloat() ?: width
        val viewportHeight = parser.getAttributeValue(ns, "viewportHeight")?.toFloat() ?: height

        val builder = ImageVector.Builder(
            name = parser.getAttributeValue(ns, "name") ?: "vector",
            defaultWidth = width.dp,
            defaultHeight = height.dp,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            tintColor = parser.getAttributeValue(ns, "tint")?.let { parseColor(context, it) } ?: Color.Unspecified,
            tintBlendMode = parseBlendMode(parser.getAttributeValue(ns, "tintMode")),
            autoMirror = parser.getAttributeValue(ns, "autoMirrored")?.toBoolean() ?: false
        )

        val extraGroupsStack = mutableListOf<Int>()
        extraGroupsStack.add(0)

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "group" -> {
                        builder.addGroup(
                            name = parser.getAttributeValue(ns, "name") ?: "",
                            rotate = parser.getAttributeValue(ns, "rotation")?.toFloat() ?: 0f,
                            pivotX = parser.getAttributeValue(ns, "pivotX")?.toFloat() ?: 0f,
                            pivotY = parser.getAttributeValue(ns, "pivotY")?.toFloat() ?: 0f,
                            scaleX = parser.getAttributeValue(ns, "scaleX")?.toFloat() ?: 1f,
                            scaleY = parser.getAttributeValue(ns, "scaleY")?.toFloat() ?: 1f,
                            translationX = parser.getAttributeValue(ns, "translateX")?.toFloat() ?: 0f,
                            translationY = parser.getAttributeValue(ns, "translateY")?.toFloat() ?: 0f
                        )
                        extraGroupsStack.add(0)
                    }
                    "path" -> {
                        val pathData = parser.getAttributeValue(ns, "pathData")
                        if (pathData != null) {
                            builder.addPath(
                                pathData = addPathNodes(pathData),
                                name = parser.getAttributeValue(ns, "name") ?: "",
                                fill = parser.getAttributeValue(ns, "fillColor")?.let { obtainBrush(context, it) },
                                fillAlpha = parser.getAttributeValue(ns, "fillAlpha")?.toFloat() ?: 1f,
                                stroke = parser.getAttributeValue(ns, "strokeColor")?.let { obtainBrush(context, it) },
                                strokeAlpha = parser.getAttributeValue(ns, "strokeAlpha")?.toFloat() ?: 1f,
                                strokeLineWidth = parser.getAttributeValue(ns, "strokeWidth")?.toFloat() ?: 0f,
                                strokeLineCap = parseStrokeCap(parser.getAttributeValue(ns, "strokeLineCap")),
                                strokeLineJoin = parseStrokeJoin(parser.getAttributeValue(ns, "strokeLineJoin")),
                                strokeLineMiter = parser.getAttributeValue(ns, "strokeMiterLimit")?.toFloat() ?: 4f,
                                pathFillType = if (parser.getAttributeValue(ns, "fillType") == "evenOdd") PathFillType.EvenOdd else PathFillType.NonZero
                            )
                        }
                    }
                    "clip-path" -> {
                        val pathData = parser.getAttributeValue(ns, "pathData")
                        if (pathData != null) {
                            builder.addGroup(clipPathData = addPathNodes(pathData))
                            if (extraGroupsStack.isNotEmpty()) {
                                extraGroupsStack[extraGroupsStack.size - 1]++
                            }
                        }
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                if (parser.name == "vector") break
                if (parser.name == "group") {
                    if (extraGroupsStack.isNotEmpty()) {
                        val extras = extraGroupsStack.removeAt(extraGroupsStack.size - 1)
                        repeat(extras + 1) { builder.clearGroup() }
                    }
                }
            }
            eventType = parser.next()
        }
        return builder.build()
    }

    private fun imageVectorToDrawable(context: Context, imageVector: ImageVector): Drawable {
        val density = Density(context.resources.displayMetrics.density)
        val widthPx = with(density) { imageVector.defaultWidth.toPx() }.toInt().coerceAtLeast(1)
        val heightPx = with(density) { imageVector.defaultHeight.toPx() }.toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        val drawScope = CanvasDrawScope()
        drawScope.draw(density, LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(widthPx.toFloat(), heightPx.toFloat())) {
            renderVectorGroup(imageVector.root)
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderVectorGroup(group: VectorGroup) {
        withTransform({
            translate(group.translationX, group.translationY)
            rotate(group.rotation, androidx.compose.ui.geometry.Offset(group.pivotX, group.pivotY))
            scale(group.scaleX, group.scaleY, androidx.compose.ui.geometry.Offset(group.pivotX, group.pivotY))
        }) {
            for (node in group) {
                when (node) {
                    is VectorPath -> {
                        val path = Path()
                        addPathNodesToPath(node.pathData, path)
                        path.fillType = node.pathFillType
                        drawPath(
                            path = path,
                            brush = node.fill ?: SolidColor(Color.Transparent),
                            alpha = node.fillAlpha,
                            style = Fill
                        )
                        if (node.stroke != null && node.strokeLineWidth > 0) {
                            drawPath(
                                path = path,
                                brush = node.stroke!!,
                                alpha = node.strokeAlpha,
                                style = Stroke(
                                    width = node.strokeLineWidth,
                                    cap = node.strokeLineCap,
                                    join = node.strokeLineJoin,
                                    miter = node.strokeLineMiter
                                )
                            )
                        }
                    }
                    is VectorGroup -> renderVectorGroup(node)
                }
            }
        }
    }

    private fun addPathNodesToPath(nodes: List<PathNode>, path: Path) {
        var currentX = 0f
        var currentY = 0f
        var segmentX = 0f
        var segmentY = 0f

        for (node in nodes) {
            when (node) {
                is PathNode.Close -> {
                    currentX = segmentX
                    currentY = segmentY
                    path.close()
                }
                is PathNode.MoveTo -> {
                    currentX = node.x
                    currentY = node.y
                    segmentX = node.x
                    segmentY = node.y
                    path.moveTo(node.x, node.y)
                }
                is PathNode.RelativeMoveTo -> {
                    currentX += node.dx
                    currentY += node.dy
                    segmentX = currentX
                    segmentY = currentY
                    path.relativeMoveTo(node.dx, node.dy)
                }
                is PathNode.LineTo -> {
                    currentX = node.x
                    currentY = node.y
                    path.lineTo(node.x, node.y)
                }
                is PathNode.RelativeLineTo -> {
                    currentX += node.dx
                    currentY += node.dy
                    path.relativeLineTo(node.dx, node.dy)
                }
                is PathNode.HorizontalTo -> {
                    currentX = node.x
                    path.lineTo(node.x, currentY)
                }
                is PathNode.RelativeHorizontalTo -> {
                    currentX += node.dx
                    path.relativeLineTo(node.dx, 0f)
                }
                is PathNode.VerticalTo -> {
                    currentY = node.y
                    path.lineTo(currentX, node.y)
                }
                is PathNode.RelativeVerticalTo -> {
                    currentY += node.dy
                    path.relativeLineTo(0f, node.dy)
                }
                is PathNode.CurveTo -> {
                    path.cubicTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                    currentX = node.x3
                    currentY = node.y3
                }
                is PathNode.RelativeCurveTo -> {
                    path.relativeCubicTo(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
                    currentX += node.dx3
                    currentY += node.dy3
                }
                is PathNode.QuadTo -> {
                    path.quadraticTo(node.x1, node.y1, node.x2, node.y2)
                    currentX = node.x2
                    currentY = node.y2
                }
                is PathNode.RelativeQuadTo -> {
                    path.relativeQuadraticTo(node.dx1, node.dy1, node.dx2, node.dy2)
                    currentX += node.dx2
                    currentY += node.dy2
                }
                is PathNode.ArcTo -> {
                    // Simplified ArcTo: path.arcTo(rect, start, sweep, false) is available in Compose
                    // but SVG parameters are different. Skip for now.
                }
                else -> {}
            }
        }
    }

    private fun String.parseDimension(): Float = filter { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: 0f

    private fun parseColor(context: Context, colorStr: String): Color {
        return try {
            if (colorStr.startsWith("#")) {
                Color(android.graphics.Color.parseColor(colorStr))
            } else if (colorStr.startsWith("resourceId:")) {
                val resId = colorStr.substringAfter("0x").toLong(16).toInt()
                if ((resId shr 24) == 0x01) {
                    val color = androidx.core.content.res.ResourcesCompat.getColor(context.resources, resId, null)
                    Color(color)
                } else Color.Transparent
            } else Color.Transparent
        } catch (e: Exception) { Color.Transparent }
    }

    private fun obtainBrush(
        context: Context,
        colorStr: String,
        apkInfo: ApkInfo? = null,
        locale: Locale = Locale.getDefault(),
        subResourceProvider: ((String) -> ByteArray?)? = null
    ): Brush? {
        val color = parseColor(context, colorStr)
        if (color != Color.Transparent) return SolidColor(color)
        
        if (colorStr.endsWith(".xml") && subResourceProvider != null && apkInfo != null) {
            android.util.Log.d("AppLog", "icon fetching: attempting to parse complex color: $colorStr")
            val bytes = subResourceProvider(colorStr)
            if (bytes != null) {
                return tryParseComplexColor(context, bytes, apkInfo, locale, subResourceProvider)
            } else {
                android.util.Log.d("AppLog", "icon fetching: subResourceProvider returned null for $colorStr")
            }
        }
        return null
    }

    private fun tryParseComplexColor(
        context: Context,
        bytes: ByteArray,
        apkInfo: ApkInfo,
        locale: Locale,
        subResourceProvider: ((String) -> ByteArray?)?
    ): Brush? {
        val streamer = GradientStreamer(context, apkInfo, locale, subResourceProvider)
        val parser = BinaryXmlParser(ByteBuffer.wrap(bytes), apkInfo.resourceTable, streamer, locale)
        try {
            parser.parse()
            return streamer.brush
        } catch (e: Exception) {
            android.util.Log.d("AppLog", "icon fetching: failed to parse complex color: ${e.message}")
        }
        return null
    }

    private class GradientStreamer(
        private val context: Context,
        private val apkInfo: ApkInfo,
        private val locale: Locale,
        private val subResourceProvider: ((String) -> ByteArray?)?
    ) : XmlStreamer {
        var brush: Brush? = null
        private var type: String? = null
        private var startColor: Color = Color.Transparent
        private var endColor: Color = Color.Transparent
        private var centerColor: Color? = null
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var centerX = 0f
        private var centerY = 0f
        private var gradientRadius = 0f
        private val stops = mutableListOf<Float>()
        private val colors = mutableListOf<Color>()

        override fun onStartTag(tag: XmlNodeStartTag) {
            val attr = tag.attributes
            when (tag.name) {
                "gradient" -> {
                    type = attr.getString("type") ?: "linear"
                    startColor = attr.getString("startColor")?.let { parseColor(context, it) } ?: Color.Transparent
                    endColor = attr.getString("endColor")?.let { parseColor(context, it) } ?: Color.Transparent
                    centerColor = attr.getString("centerColor")?.let { parseColor(context, it) }
                    startX = attr.getString("startX")?.toFloat() ?: 0f
                    startY = attr.getString("startY")?.toFloat() ?: 0f
                    endX = attr.getString("endX")?.toFloat() ?: 0f
                    endY = attr.getString("endY")?.toFloat() ?: 0f
                    centerX = attr.getString("centerX")?.toFloat() ?: 0f
                    centerY = attr.getString("centerY")?.toFloat() ?: 0f
                    gradientRadius = attr.getString("gradientRadius")?.toFloat() ?: 0f
                }
                "item" -> {
                    val offset = attr.getString("offset")?.toFloat() ?: 0f
                    val colorStr = attr.getString("color")
                    val color = if (colorStr != null) {
                        obtainBrush(context, colorStr, apkInfo, locale, subResourceProvider)?.let {
                            if (it is SolidColor) it.value else Color.Transparent
                        } ?: Color.Transparent
                    } else Color.Transparent
                    stops.add(offset)
                    colors.add(color)
                }
            }
        }

        override fun onEndTag(tag: XmlNodeEndTag) {
            if (tag.name == "gradient") {
                val finalColors = if (colors.isNotEmpty()) colors else {
                    centerColor?.let { listOf(startColor, it, endColor) } ?: listOf(startColor, endColor)
                }
                val finalStops = if (stops.isNotEmpty()) stops else {
                    if (centerColor != null) listOf(0f, 0.5f, 1f) else listOf(0f, 1f)
                }
                
                brush = when (type) {
                    "radial" -> Brush.radialGradient(
                        colorStops = finalStops.zip(finalColors).toTypedArray(),
                        center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                        radius = gradientRadius
                    )
                    "sweep" -> Brush.sweepGradient(
                        colorStops = finalStops.zip(finalColors).toTypedArray(),
                        center = androidx.compose.ui.geometry.Offset(centerX, centerY)
                    )
                    else -> Brush.linearGradient(
                        colorStops = finalStops.zip(finalColors).toTypedArray(),
                        start = androidx.compose.ui.geometry.Offset(startX, startY),
                        end = androidx.compose.ui.geometry.Offset(endX, endY)
                    )
                }
            }
        }

        override fun onCData(xmlCData: net.dongliu.apk.parser.struct.xml.XmlCData) {}
        override fun onNamespaceStart(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag) {}
        override fun onNamespaceEnd(tag: net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag) {}
    }

    private fun parseBlendMode(modeStr: String?): BlendMode = when (modeStr) {
        "src_over" -> BlendMode.SrcOver
        "src_in" -> BlendMode.SrcIn
        "src_atop" -> BlendMode.SrcAtop
        "multiply" -> BlendMode.Modulate
        "screen" -> BlendMode.Screen
        "add" -> BlendMode.Plus
        else -> BlendMode.SrcIn
    }

    private fun parseStrokeCap(capStr: String?): StrokeCap = when (capStr) {
        "butt", "0" -> StrokeCap.Butt
        "round", "1" -> StrokeCap.Round
        "square", "2" -> StrokeCap.Square
        else -> StrokeCap.Butt
    }

    private fun parseStrokeJoin(joinStr: String?): StrokeJoin = when (joinStr) {
        "miter", "0" -> StrokeJoin.Miter
        "round", "1" -> StrokeJoin.Round
        "bevel", "2" -> StrokeJoin.Bevel
        else -> StrokeJoin.Miter
    }
}
