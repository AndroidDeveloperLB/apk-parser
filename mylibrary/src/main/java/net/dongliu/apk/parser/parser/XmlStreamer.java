package net.dongliu.apk.parser.parser;

import androidx.annotation.NonNull;

import net.dongliu.apk.parser.struct.xml.XmlCData;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag;

/**
 * callback interface for parse binary xml file.
 *
 * @author dongliu
 */
public interface XmlStreamer {

    void onStartTag(@NonNull XmlNodeStartTag xmlNodeStartTag);

    void onEndTag(@NonNull XmlNodeEndTag xmlNodeEndTag);

    void onCData(@NonNull XmlCData xmlCData);

    void onNamespaceStart(@NonNull XmlNamespaceStartTag tag);

    void onNamespaceEnd(@NonNull XmlNamespaceEndTag tag);
}
