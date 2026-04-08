package net.dongliu.apk.parser.struct;

import androidx.annotation.Nullable;

/**
 * String pool.
 *
 * @author dongliu
 */
public class StringPool {
    @Nullable
    private final String[] pool;
    @Nullable
    private final StringSource source;

    public interface StringSource {
        String read(int idx);
    }

    public StringPool(final int poolSize) {
        this(poolSize, null);
    }

    public StringPool(final int poolSize, @Nullable final StringSource source) {
        this.pool = new String[poolSize];
        this.source = source;
    }

    @Nullable
    public String get(final int idx) {
        if (pool != null && idx >= 0 && idx < pool.length) {
            if (pool[idx] == null && source != null) {
                pool[idx] = source.read(idx);
            }
            return this.pool[idx];
        }
        return null;
    }

    public void set(final int idx, final String value) {
        if (pool != null && idx >= 0 && idx < pool.length) {
            this.pool[idx] = value;
        }
    }
}
