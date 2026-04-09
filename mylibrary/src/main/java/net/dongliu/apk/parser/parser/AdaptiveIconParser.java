package net.dongliu.apk.parser.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.struct.xml.Attribute;
import net.dongliu.apk.parser.struct.xml.Attributes;
import net.dongliu.apk.parser.struct.xml.XmlCData;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse adaptive icon xml file.
 *
 * @author Liu Dong dongliu@live.cn
 */
public class AdaptiveIconParser implements XmlStreamer {
    @Nullable
    private String foreground;
    @Nullable
    private String background;
    @Nullable
    private String monochrome;
    @NonNull
    private final List<String> drawables = new ArrayList<>();
    @Nullable
    private String rootTag;
    @Nullable
    private String currentSection;

    @Nullable
    public String getForeground() {
        return this.foreground;
    }

    @Nullable
    public String getBackground() {
        return this.background;
    }

    @Nullable
    public String getMonochrome() {
        return this.monochrome;
    }

    @NonNull
    public List<String> getDrawables() {
        return drawables;
    }

    @Nullable
    public String getRootTag() {
        return rootTag;
    }

    @Override
    public void onStartTag(final @NonNull XmlNodeStartTag xmlNodeStartTag) {
        if (rootTag == null) {
            rootTag = xmlNodeStartTag.name;
            android.util.Log.d("AppLog", "icon fetching: XML root tag: " + rootTag);
        }

        if ("background".equals(xmlNodeStartTag.name) || "foreground".equals(xmlNodeStartTag.name) || "monochrome".equals(xmlNodeStartTag.name)) {
            this.currentSection = xmlNodeStartTag.name;
        }

        StringBuilder sb = new StringBuilder();
        for (net.dongliu.apk.parser.struct.xml.Attribute attr : xmlNodeStartTag.attributes.attributes) {
            if (attr == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(attr.namespace).append(":").append(attr.name).append("=").append(attr.value);
        }
        android.util.Log.d("AppLog", "icon fetching: tag <" + xmlNodeStartTag.name + "> attributes: [" + sb.toString() + "]");

        final String drawable = this.getDrawable(xmlNodeStartTag);
        if (drawable != null) {
            android.util.Log.d("AppLog", "icon fetching: found drawable value: " + drawable);
            this.drawables.add(drawable);
            if ("background".equals(this.currentSection) && this.background == null) {
                this.background = drawable;
            } else if ("foreground".equals(this.currentSection) && this.foreground == null) {
                this.foreground = drawable;
            } else if ("monochrome".equals(this.currentSection) && this.monochrome == null) {
                this.monochrome = drawable;
            }
        }
    }

    @Nullable
    private String getDrawable(final XmlNodeStartTag xmlNodeStartTag) {
        final Attributes attributes = xmlNodeStartTag.attributes;
        for (final Attribute attribute : attributes.attributes) {
            if (attribute == null) continue;
            if (attribute.name.equals("drawable") || attribute.name.equals("src")) {
                return attribute.value;
            }
        }
        return null;
    }

    @Override
    public void onEndTag(@NonNull final XmlNodeEndTag xmlNodeEndTag) {
        if ("background".equals(xmlNodeEndTag.getName()) || "foreground".equals(xmlNodeEndTag.getName()) || "monochrome".equals(xmlNodeEndTag.getName())) {
            this.currentSection = null;
        }
    }

    @Override
    public void onCData(@NonNull final XmlCData xmlCData) {
    }

    @Override
    public void onNamespaceStart(@NonNull final XmlNamespaceStartTag tag) {
    }

    @Override
    public void onNamespaceEnd(@NonNull final XmlNamespaceEndTag tag) {
    }
}
