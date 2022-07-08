package net.dongliu.apk.parser.parser;

import androidx.annotation.Nullable;

import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * the xml file's namespaces.
 *
 * @author dongliu
 */
class XmlNamespaces {

    private final List<XmlNamespace> namespaces = new ArrayList<>();

    private final List<XmlNamespace> newNamespaces = new ArrayList<>();

    public void addNamespace(final XmlNamespaceStartTag tag) {
        final XmlNamespace namespace = new XmlNamespace(tag.prefix, tag.uri);
        this.namespaces.add(namespace);
        this.newNamespaces.add(namespace);
    }

    public void removeNamespace(final XmlNamespaceEndTag tag) {
        final XmlNamespace namespace = new XmlNamespace(tag.prefix, tag.uri);
        this.namespaces.remove(namespace);
        this.newNamespaces.remove(namespace);
    }

    @Nullable
    public String getPrefixViaUri(@Nullable final String uri) {
        if (uri == null) {
            return null;
        }
        for (final XmlNamespace namespace : this.namespaces) {
            if (uri.equals(namespace.uri)) {
                return namespace.prefix;
            }
        }
        return null;
    }

    public List<XmlNamespace> consumeNameSpaces() {
        if (!this.newNamespaces.isEmpty()) {
            final List<XmlNamespace> xmlNamespaces = new ArrayList<>(this.newNamespaces);
            this.newNamespaces.clear();
            return xmlNamespaces;
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * one namespace
     */
    public static class XmlNamespace {
        @Nullable
        public final String prefix;
        @Nullable
        public final String uri;

        private XmlNamespace(@Nullable final String prefix, @Nullable final String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof XmlNamespace)) return false;
            final XmlNamespace that = (XmlNamespace) o;
            return Objects.equals(this.prefix, that.prefix) && Objects.equals(this.uri, that.uri);
        }

        @Override
        public int hashCode() {
            int result = this.prefix == null ? 0 : this.prefix.hashCode();
            result = 31 * result + (this.uri == null ? 0 : this.uri.hashCode());
            return result;
        }
    }
}
