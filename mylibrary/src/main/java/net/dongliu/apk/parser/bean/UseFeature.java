package net.dongliu.apk.parser.bean;

import androidx.annotation.NonNull;

/**
 * the permission used by apk
 *
 * @author dongliu
 */
public class UseFeature {
    private final String name;
    private final boolean required;

    public UseFeature(final String name, final boolean required) {
        this.name = name;
        this.required = required;
    }

    public String getName() {
        return this.name;
    }

    public boolean isRequired() {
        return this.required;
    }

    @NonNull
    @Override
    public String toString() {
        return this.name;
    }
}
