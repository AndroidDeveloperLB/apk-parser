package net.dongliu.apk.parser.parser

import net.dongliu.apk.parser.struct.xml.XmlCData
import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag

/**
 * Parse adaptive icon xml file.
 *
 * @author Liu Dong dongliu@live.cn
 */
class AdaptiveIconParser : XmlStreamer {
    val foregroundDrawables: MutableList<String> = ArrayList<String>()
    val backgroundDrawables: MutableList<String> = ArrayList<String>()
    val monochromeDrawables: MutableList<String> = ArrayList<String>()
    val drawables: MutableList<String> = ArrayList<String>()
    var rootTag: String? = null
        private set
    private var currentSection: String? = null
    private var hasInlineContent = false

    val foreground: String?
        get() = if (this.foregroundDrawables.isEmpty()) null else this.foregroundDrawables.get(0)

    val background: String?
        get() = if (this.backgroundDrawables.isEmpty()) null else this.backgroundDrawables.get(0)

    val monochrome: String?
        get() = if (this.monochromeDrawables.isEmpty()) null else this.monochromeDrawables.get(0)

    fun hasInlineContent(): Boolean {
        return hasInlineContent
    }

    override fun onStartTag(xmlNodeStartTag: XmlNodeStartTag) {
        if (rootTag == null) {
            rootTag = xmlNodeStartTag.name
            // android.util.Log.d("AppLog", "icon fetching: XML root tag: " + rootTag);
        }

        if ("background" == xmlNodeStartTag.name || "foreground" == xmlNodeStartTag.name || "monochrome" == xmlNodeStartTag.name) {
            this.currentSection = xmlNodeStartTag.name
        }

        val drawable: String? = this.getFoundDrawable(xmlNodeStartTag)
        if (drawable != null) {
            // android.util.Log.d("AppLog", "icon fetching: found drawable value: " + drawable + " in section: " + currentSection + " (tag: <" + xmlNodeStartTag.name + ">)");
            this.drawables.add(drawable)
            when (this.currentSection) {
                "background" -> {
                    this.backgroundDrawables.add(drawable)
                }
                "foreground" -> {
                    this.foregroundDrawables.add(drawable)
                }
                "monochrome" -> {
                    this.monochromeDrawables.add(drawable)
                }
            }
        } else {
            // No direct drawable attribute found.
            val name = xmlNodeStartTag.name
            if ("vector" == name || "shape" == name || "animated-vector" == name || "gradient" == name || "path" == name
                    || "inset" == name || "rotate" == name || "layer-list" == name || "item" == name || "clip-path" == name || "group" == name) {
                // If it's a known drawing tag and doesn't have a drawable/src attribute, it's likely inlined content.
                hasInlineContent = true
                // android.util.Log.d("AppLog", "icon fetching: detected inline content: <" + name + "> in section: " + currentSection);
            }
        }

        val sb = StringBuilder()
        for (attr in xmlNodeStartTag.attributes.attributes) {
            if (attr == null) continue
            if (sb.length > 0) sb.append(", ")
            sb.append(attr.namespace).append(":").append(attr.name).append("=").append(attr.value)
        }
        // android.util.Log.d("AppLog", "icon fetching: tag <" + xmlNodeStartTag.name + "> attributes: [" + sb.toString() + "]");
    }

    private fun getFoundDrawable(xmlNodeStartTag: XmlNodeStartTag): String? {
        val attributes = xmlNodeStartTag.attributes
        for (attribute in attributes.attributes) {
            if (attribute == null) continue
            if (attribute.name == "drawable" || attribute.name == "src" || attribute.name == "color") {
                val value = attribute.value
                if (value != null && value.startsWith("resourceId:0x1")) {
                    // System resource ID that didn't resolve in local table
                    return value
                }
                return value
            }
        }
        return null
    }

    override fun onEndTag(xmlNodeEndTag: XmlNodeEndTag) {
        if ("background" == xmlNodeEndTag.getName() || "foreground" == xmlNodeEndTag.getName() || "monochrome" == xmlNodeEndTag.getName()) {
            this.currentSection = null
        }
    }

    override fun onCData(xmlCData: XmlCData) {
    }

    override fun onNamespaceStart(tag: XmlNamespaceStartTag) {
    }

    override fun onNamespaceEnd(tag: XmlNamespaceEndTag) {
    }
}
