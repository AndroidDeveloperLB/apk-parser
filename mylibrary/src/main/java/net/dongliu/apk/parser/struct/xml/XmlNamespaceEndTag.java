package net.dongliu.apk.parser.struct.xml;

import androidx.annotation.NonNull;

/**
 * @author dongliu
 */
public class XmlNamespaceEndTag {
    private String prefix;
    private String uri;

    public String getPrefix() {
        return this.prefix;
    }

    public void setPrefix(final String prefix) {
        this.prefix = prefix;
    }

    public String getUri() {
        return this.uri;
    }

    public void setUri(final String uri) {
        this.uri = uri;
    }

    @NonNull
    @Override
    public String toString() {
        return this.prefix + "=" + this.uri;
    }
}
