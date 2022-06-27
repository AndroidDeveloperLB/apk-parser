package net.dongliu.apk.parser.parser;

import androidx.annotation.NonNull;

import net.dongliu.apk.parser.struct.xml.XmlCData;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag;

/**
 * @author dongliu
 */
public class CompositeXmlStreamer implements XmlStreamer {

    public final XmlStreamer[] xmlStreamers;

    public CompositeXmlStreamer(final XmlStreamer... xmlStreamers) {
        this.xmlStreamers = xmlStreamers;
    }

    @Override
    public void onStartTag(@NonNull final XmlNodeStartTag xmlNodeStartTag) {
        for (final XmlStreamer xmlStreamer : this.xmlStreamers) {
            xmlStreamer.onStartTag(xmlNodeStartTag);
        }
    }

    @Override
    public void onEndTag(final @NonNull XmlNodeEndTag xmlNodeEndTag) {
        for (final XmlStreamer xmlStreamer : this.xmlStreamers) {
            xmlStreamer.onEndTag(xmlNodeEndTag);
        }
    }

    @Override
    public void onCData(final @NonNull XmlCData xmlCData) {
        for (final XmlStreamer xmlStreamer : this.xmlStreamers) {
            xmlStreamer.onCData(xmlCData);
        }
    }

    @Override
    public void onNamespaceStart(@NonNull final XmlNamespaceStartTag tag) {
        for (final XmlStreamer xmlStreamer : this.xmlStreamers) {
            xmlStreamer.onNamespaceStart(tag);
        }
    }

    @Override
    public void onNamespaceEnd(final @NonNull XmlNamespaceEndTag tag) {
        for (final XmlStreamer xmlStreamer : this.xmlStreamers) {
            xmlStreamer.onNamespaceEnd(tag);
        }
    }
}
