package net.dongliu.apk.parser.struct;

import androidx.annotation.NonNull;

/**
 * String pool.
 *
 * @author dongliu
 */
public class StringPool {
    @NonNull
    private final String[] pool;

    public StringPool(final int poolSize) {
        this.pool = new String[poolSize];
    }

    public String get(final int idx) {
        if (idx >= 0 && idx < pool.length) {
            return this.pool[idx];
        }
        return null;
    }

    public void set(final int idx, final String value) {
        this.pool[idx] = value;
    }
}
