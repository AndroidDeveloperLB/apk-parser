package net.dongliu.apk.parser.bean;


import androidx.annotation.NonNull;

/**
 * the glEsVersion apk used.
 *
 * @author dongliu
 */
public class GlEsVersion {
    private final int major;
    private final int minor;
    private final boolean required;

    public GlEsVersion(final int major, final int minor, final boolean required) {
        this.major = major;
        this.minor = minor;
        this.required = required;
    }


    public int getMajor() {
        return this.major;
    }

    public int getMinor() {
        return this.minor;
    }

    public boolean isRequired() {
        return this.required;
    }

    @NonNull
    @Override
    public String toString() {
        return this.major + "." + this.minor;
    }

}
