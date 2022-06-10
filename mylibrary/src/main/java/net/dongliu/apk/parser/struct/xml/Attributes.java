package net.dongliu.apk.parser.struct.xml;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * xml node attributes
 *
 * @author dongliu
 */
public class Attributes {

    private final Attribute[] attributes;

    public Attributes(final int size) {
        this.attributes = new Attribute[size];
    }

    public void set(final int i, final Attribute attribute) {
        this.attributes[i] = attribute;
    }

    @Nullable
    public Attribute get(final @NonNull String name) {
        for (final Attribute attribute : this.attributes) {
            if (attribute.getName().equals(name)) {
                return attribute;
            }
        }
        return null;
    }


    /**
     * Get attribute with name, return value as string
     */
    @Nullable
    public String getString(final @NonNull String name) {
        final Attribute attribute = this.get(name);
        if (attribute == null) {
            return null;
        }
        return attribute.getValue();
    }

    public int size() {
        return this.attributes.length;
    }

    public boolean getBoolean(final @NonNull String name, final boolean b) {
        final String value = this.getString(name);
        return value == null ? b : Boolean.parseBoolean(value);
    }

    @Nullable
    public Integer getInt(final @NonNull String name) {
        final String value = this.getString(name);
        if (value == null) {
            return null;
        }
        if (value.startsWith("0x")) {
            return Integer.valueOf(value.substring(2), 16);
        }
        return Integer.valueOf(value);
    }

    @Nullable
    public Long getLong(final @NonNull String name) {
        final String value = this.getString(name);
        if (value == null) {
            return null;
        }
        if (value.startsWith("0x")) {
            return Long.valueOf(value.substring(2), 16);
        }
        return Long.valueOf(value);
    }

    /**
     * return all attributes
     */
    public Attribute[] values() {
        return this.attributes;
    }
}

