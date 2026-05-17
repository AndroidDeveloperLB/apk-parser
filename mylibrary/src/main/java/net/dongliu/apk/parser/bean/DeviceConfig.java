package net.dongliu.apk.parser.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Encapsulates device configuration for resource resolution.
 *
 * @author dongliu
 */
public class DeviceConfig {
    @Nullable
    private final Locale locale;
    private final int mcc;
    private final int mnc;
    private final int density;
    private final int uiMode;

    private DeviceConfig(@Nullable Locale locale, int mcc, int mnc, int density, int uiMode) {
        this.locale = locale;
        this.mcc = mcc;
        this.mnc = mnc;
        this.density = density;
        this.uiMode = uiMode;
    }

    @NonNull
    public static DeviceConfig defaultLocale(@Nullable Locale locale) {
        return new DeviceConfig(locale, 0, 0, 0, 0);
    }

    @NonNull
    public static DeviceConfig create(@Nullable Locale locale, int mcc, int mnc, int density) {
        return new DeviceConfig(locale, mcc, mnc, density, 0);
    }

    @NonNull
    public static DeviceConfig create(@Nullable Locale locale, int mcc, int mnc, int density, int uiMode) {
        return new DeviceConfig(locale, mcc, mnc, density, uiMode);
    }

    @Nullable
    public Locale getLocale() {
        return locale;
    }

    public int getMcc() {
        return mcc;
    }

    public int getMnc() {
        return mnc;
    }

    public int getDensity() {
        return density;
    }

    public int getUiMode() {
        return uiMode;
    }

    @NonNull
    @Override
    public String toString() {
        return "DeviceConfig{" +
                "locale=" + locale +
                ", mcc=" + mcc +
                ", mnc=" + mnc +
                ", density=" + density +
                '}';
    }
}
