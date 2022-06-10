package net.dongliu.apk.parser.utils;


import java.util.Iterator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class Strings {

    /**
     * Copied fom commons StringUtils
     * <p>Joins the elements of the provided {@code Iterable} into
     * a single String containing the provided elements.</p>
     */
    @Nullable
    public static String join(final @NonNull Iterable<?> iterable, final @NonNull String separator) {
        return Strings.join(iterable.iterator(), separator);
    }

    /**
     * Copied fom commons StringUtils
     */
    @Nullable
    public static String join(final @NonNull Iterator<?> iterator, final @Nullable String separator) {
        if (!iterator.hasNext()) {
            return "";
        }
        final Object first = iterator.next();
        if (!iterator.hasNext()) {
            return first == null ? null : first.toString();
        }

        // two or more elements
        final StringBuilder buf = new StringBuilder(256); // Java default is 16, probably too small
        if (first != null) {
            buf.append(first);
        }

        while (iterator.hasNext()) {
            if (separator != null) {
                buf.append(separator);
            }
            final Object obj = iterator.next();
            if (obj != null) {
                buf.append(obj);
            }
        }
        return buf.toString();
    }

    public static boolean isNumeric(final @Nullable CharSequence cs) {
        if (Strings.isEmpty(cs)) {
            return false;
        }
        final int sz = cs.length();
        for (int i = 0; i < sz; i++) {
            if (!Character.isDigit(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isEmpty(final @Nullable CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    @NonNull
    public static String substringBefore(final @NonNull String str, final @NonNull String separator) {
        if (str.isEmpty()) {
            return str;
        }
        if (separator.isEmpty()) {
            return "";
        }
        final int pos = str.indexOf(separator);
        if (pos == -1) {
            return str;
        }
        return str.substring(0, pos);
    }
}
