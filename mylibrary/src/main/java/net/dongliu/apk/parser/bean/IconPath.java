package net.dongliu.apk.parser.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Icon path, and density
 */
public class IconPath {
    @Nullable
    private final String path;
    private final int density;

    public IconPath(final @Nullable String path, final int density) {
        this.path = path;
        this.density = density;
    }

    /**
     * The icon path in apk file
     */
    @Nullable
    public String getPath() {
        return this.path;
    }

    /**
     * Return the density this icon for. 0 means default icon.
     * see {@link net.dongliu.apk.parser.struct.resource.Densities} for more density values.
     */
    public int getDensity() {
        return this.density;
    }

    @NonNull
    @Override
    public String toString() {
        return "IconPath{" +
                "path='" + this.path + '\'' +
                ", density=" + this.density +
                '}';
    }
}
