package net.dongliu.apk.parser.bean;


import java.io.Serializable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Android adaptive icon, from android 8.0
 */
public class AdaptiveIcon implements IconFace, Serializable {
    private static final long serialVersionUID = 4185750290211529320L;
    @Nullable
    private final Icon foreground;
    @Nullable
    private final Icon background;

    public AdaptiveIcon(@Nullable final Icon foreground, @Nullable final Icon background) {
        this.foreground = foreground;
        this.background = background;
    }


    /**
     * The foreground icon
     */
    @Nullable
    public Icon getForeground() {
        return this.foreground;
    }

    /**
     * The background icon
     */
    @Nullable
    public Icon getBackground() {
        return this.background;
    }

    @NonNull
    @Override
    public String toString() {
        return "AdaptiveIcon{" +
                "foreground=" + this.foreground +
                ", background=" + this.background +
                '}';
    }

    @Override
    public boolean isFile() {
        return this.foreground.isFile();
    }

    @Override
    @Nullable
    public byte[] getData() {
        return this.foreground.getData();
    }

    @Override
    public String getPath() {
        return this.foreground.getPath();
    }
}
