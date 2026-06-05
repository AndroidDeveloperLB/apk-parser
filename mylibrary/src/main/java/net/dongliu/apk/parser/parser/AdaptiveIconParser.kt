package net.dongliu.apk.parser.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    @NonNull
    private final List<String> foregroundDrawables = new ArrayList<>();
    @NonNull
    private final List<String> backgroundDrawables = new ArrayList<>();
    @NonNull
    private final List<String> monochromeDrawables = new ArrayList<>();
    @NonNull
    private final List<String> drawables = new ArrayList<>();
    @Nullable
    private String rootTag;
    @Nullable
    private String currentSection;
    private boolean hasInlineContent = false;

    @Nullable
    public String getForeground() {
        return this.foregroundDrawables.isEmpty() ? null : this.foregroundDrawables.get(0);
    }

    @Nullable
    public String getBackground() {
        return this.backgroundDrawables.isEmpty() ? null : this.backgroundDrawables.get(0);
    }

    @Nullable
    public String getMonochrome() {
        return this.monochromeDrawables.isEmpty() ? null : this.monochromeDrawables.get(0);
    }

    @NonNull
    public List<String> getForegroundDrawables() {
        return foregroundDrawables;
    }

    @NonNull
    public List<String> getBackgroundDrawables() {
        return backgroundDrawables;
    }

    @NonNull
    public List<String> getMonochromeDrawables() {
        return monochromeDrawables;
    }

    @NonNull
    public List<String> getDrawables() {
        return drawables;
    }

    @Nullable
    public String getRootTag() {
        return rootTag;
    }

    public boolean hasInlineContent() {
        return hasInlineContent;
    }

    @Override
    public void onStartTag(final @NonNull XmlNodeStartTag xmlNodeStartTag) {
        if (rootTag == null) {
            rootTag = xmlNodeStartTag.name;
            // android.util.Log.d("AppLog", "icon fetching: XML root tag: " + rootTag);
        }

        if ("background".equals(xmlNodeStartTag.name) || "foreground".equals(xmlNodeStartTag.name) || "monochrome".equals(xmlNodeStartTag.name)) {
            this.currentSection = xmlNodeStartTag.name;
        }

        final String drawable = this.getFoundDrawable(xmlNodeStartTag);
        if (drawable != null) {
            // android.util.Log.d("AppLog", "icon fetching: found drawable value: " + drawable + " in section: " + currentSection + " (tag: <" + xmlNodeStartTag.name + ">)");
            this.drawables.add(drawable);
            if ("background".equals(this.currentSection)) {
                this.backgroundDrawables.add(drawable);
            } else if ("foreground".equals(this.currentSection)) {
                this.foregroundDrawables.add(drawable);
            } else if ("monochrome".equals(this.currentSection)) {
                this.monochromeDrawables.add(drawable);
            }
        } else {
            // No direct drawable attribute found.
            String name = xmlNodeStartTag.name;
            if ("vector".equals(name) || "shape".equals(name) || "animated-vector".equals(name) || "gradient".equals(name) || "path".equals(name)
                    || "inset".equals(name) || "rotate".equals(name) || "layer-list".equals(name) || "item".equals(name) || "clip-path".equals(name) || "group".equals(name)) {
                // If it's a known drawing tag and doesn't have a drawable/src attribute, it's likely inlined content.
                hasInlineContent = true;
                // android.util.Log.d("AppLog", "icon fetching: detected inline content: <" + name + "> in section: " + currentSection);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (net.dongliu.apk.parser.struct.xml.Attribute attr : xmlNodeStartTag.attributes.attributes) {
            if (attr == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(attr.namespace).append(":").append(attr.name).append("=").append(attr.value);
        }
        // android.util.Log.d("AppLog", "icon fetching: tag <" + xmlNodeStartTag.name + "> attributes: [" + sb.toString() + "]");
    }

    @Nullable
    private String getFoundDrawable(final XmlNodeStartTag xmlNodeStartTag) {
        final Attributes attributes = xmlNodeStartTag.attributes;
        for (final net.dongliu.apk.parser.struct.xml.Attribute attribute : attributes.attributes) {
            if (attribute == null) continue;
            if (attribute.name.equals("drawable") || attribute.name.equals("src") || attribute.name.equals("color")) {
                String value = attribute.value;
                if (value != null && value.startsWith("resourceId:0x1")) {
                    // System resource ID that didn't resolve in local table
                    return value;
                }
                return value;
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
