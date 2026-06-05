package net.dongliu.apk.parser.bean

import java.util.Locale
import java.util.Objects

/**
 * Encapsulates device configuration for resource resolution.
 *
 * @author dongliu
 */
class DeviceConfig private constructor(@JvmField val locale: Locale?, @JvmField val mcc: Int,
                                       val mnc: Int, @JvmField val density: Int, @JvmField val uiMode: Int) {
    override fun equals(o: Any?): Boolean {
        if (o !is DeviceConfig) return false
        return mcc == o.mcc && mnc == o.mnc && density == o.density && uiMode == o.uiMode && locale == o.locale
    }

    override fun hashCode(): Int {
        return Objects.hash(locale, mcc, mnc, density, uiMode)
    }

    override fun toString(): String {
        return "DeviceConfig{" +
                "locale=" + locale +
                ", mcc=" + mcc +
                ", mnc=" + mnc +
                ", density=" + density +
                '}'
    }

    companion object {
        @JvmStatic
        fun defaultLocale(locale: Locale?): DeviceConfig {
            return DeviceConfig(locale, 0, 0, 0, 0)
        }

        @JvmStatic
        fun create(locale: Locale?, mcc: Int, mnc: Int, density: Int): DeviceConfig {
            return DeviceConfig(locale, mcc, mnc, density, 0)
        }

        fun create(locale: Locale?, mcc: Int, mnc: Int, density: Int, uiMode: Int): DeviceConfig {
            return DeviceConfig(locale, mcc, mnc, density, uiMode)
        }
    }
}
