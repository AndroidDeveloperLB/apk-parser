package net.dongliu.apk.parser.parser;

import androidx.annotation.Nullable;

import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        private final String prefix;
        @Nullable
        private final String uri;

        private XmlNamespace(@Nullable final String prefix, @Nullable final String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }

        @Nullable
        public String getPrefix() {
            return this.prefix;
        }

        @Nullable
        public String getUri() {
            return this.uri;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || this.getClass() != o.getClass()) return false;
            final XmlNamespace namespace = (XmlNamespace) o;
            if (this.prefix == null && namespace.prefix != null) return false;
            if (this.uri == null && namespace.uri != null) return false;
            if (this.prefix != null && !this.prefix.equals(namespace.prefix)) return false;
            return this.uri == null || this.uri.equals(namespace.uri);
        }

        @Override
        public int hashCode() {
            int result = this.prefix.hashCode();
            result = 31 * result + this.uri.hashCode();
            return result;
        }
    }
}
