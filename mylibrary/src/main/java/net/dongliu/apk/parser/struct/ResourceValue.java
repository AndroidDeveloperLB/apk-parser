package net.dongliu.apk.parser.struct;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.bean.DeviceConfig;
import net.dongliu.apk.parser.struct.resource.Densities;
import net.dongliu.apk.parser.struct.resource.ResourceTable;
import net.dongliu.apk.parser.struct.xml.Attribute;
import net.dongliu.apk.parser.utils.Locales;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource entity, contains the resource id, should retrieve the value from resource table, or string pool if it is a string resource.
 *
 * @author dongliu
 */
public abstract class ResourceValue {
    /**
     * The raw integer value of the resource.
     * Made public to allow easy access in rendering logic without reflection.
     */
    public final int value;

    protected ResourceValue(final int value) {
        this.value = value;
    }

    /**
     * get value as string.
     */
    @Nullable
    public abstract String toStringValue(ResourceTable resourceTable, @Nullable DeviceConfig config);

    /**
     * Internal overload with visited Set for cycle detection.
     */
    @Nullable
    public String toStringValue(ResourceTable resourceTable, @Nullable DeviceConfig config, Set<Long> visited) {
        return toStringValue(resourceTable, config);
    }

    @NonNull
    public static ResourceValue decimal(final int value) {
        return new DecimalResourceValue(value);
    }

    @NonNull
    public static ResourceValue hexadecimal(final int value) {
        return new HexadecimalResourceValue(value);
    }

    @NonNull
    public static ResourceValue bool(final int value) {
        return new BooleanResourceValue(value);
    }

    @NonNull
    public static ResourceValue string(final int value, final StringPool stringPool) {
        return new StringResourceValue(value, stringPool);
    }

    @NonNull
    public static ResourceValue reference(final int value) {
        return new ReferenceResourceValue(value);
    }

    @NonNull
    public static ResourceValue attribute(final int value) {
        return new AttributeResourceValue(value);
    }

    @NonNull
    public static ResourceValue floatValue(final int value) {
        return new FloatResourceValue(value);
    }

    @NonNull
    public static ResourceValue nullValue() {
        return NullResourceValue.instance;
    }

    @NonNull
    public static ResourceValue rgb(final int value, final int len) {
        return new RGBResourceValue(value, len);
    }

    @NonNull
    public static ResourceValue dimension(final int value) {
        return new DimensionValue(value);
    }

    @NonNull
    public static ResourceValue fraction(final int value) {
        return new FractionValue(value);
    }

    @NonNull
    public static ResourceValue raw(final int value, final short type) {
        return new RawValue(value, type);
    }

    private static class DecimalResourceValue extends ResourceValue {

        private DecimalResourceValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return String.valueOf(this.value);
        }
    }

    private static class HexadecimalResourceValue extends ResourceValue {

        private HexadecimalResourceValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return "0x" + Integer.toHexString(this.value);
        }
    }

    private static class BooleanResourceValue extends ResourceValue {

        private BooleanResourceValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return String.valueOf(this.value != 0);
        }
    }

    private static class StringResourceValue extends ResourceValue {
        private final StringPool stringPool;

        private StringResourceValue(final int value, final StringPool stringPool) {
            super(value);
            this.stringPool = stringPool;
        }

        @Nullable
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            if (this.value >= 0) {
                String result = this.stringPool.get(this.value);
                return result;
            } else {
                return null;
            }
        }

        @NonNull
        @Override
        public String toString() {
            String val = this.stringPool.get(this.value);
            return this.value + ":" + val;
        }
    }

    /**
     * ReferenceResource ref one another resources, and may have different value for different resource config(locale, density, etc)
     */
    public static class ReferenceResourceValue extends ResourceValue {

        private ReferenceResourceValue(final int value) {
            super(value);
        }

        @Override
        @Nullable
        public String toStringValue(final @Nullable ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return toStringValue(resourceTable, config, new HashSet<>());
        }

        @Override
        @Nullable
        public String toStringValue(final @Nullable ResourceTable resourceTable, @Nullable final DeviceConfig config, Set<Long> visited) {
            final long resourceId = this.getReferenceResourceId();

            // Cycle detection: Stop processing if we've already seen this ID in the current branch
            if (!visited.add(resourceId)) {
                return "resourceId:0x" + Long.toHexString(resourceId);
            }

            try {
                // android system styles.
                if (resourceId > AndroidConstants.SYS_STYLE_ID_START && resourceId < AndroidConstants.SYS_STYLE_ID_END) {
                    String style = ResourceTable.sysStyle.get((int) resourceId);
                    if (style != null) {
                        return "@android:style/" + style;
                    }
                }
                final String raw = "resourceId:0x" + Long.toHexString(resourceId);
                if (resourceTable == null) {
                    return raw;
                }
                final List<ResourceTable.Resource> resources = resourceTable.getResourcesById(resourceId);
                if (resources.isEmpty()) {
                    // If it's a platform resource that wasn't in our table, we can resolve it.
                    if ((resourceId >> 24) == 0x01) {
                        return raw;
                    }
                    return null;
                }

                ResourceTable.Resource selectedResource = null;
                int currentMaxScore = 0; // Start at 0 to only accept positive matches

                // Search for the best match
                for (final ResourceTable.Resource resource : resources) {
                    final int matchScore = Locales.match(config, resource);

                    if (matchScore > currentMaxScore) {
                        selectedResource = resource;
                        currentMaxScore = matchScore;
                    } else if (matchScore > 0 && matchScore == currentMaxScore) {
                        // Tie-breaker: pick the more specific configuration
                        if (isBetterThan(resource, selectedResource, config)) {
                            selectedResource = resource;
                        }
                    }
                }

                // Recurse to get the value of the selected entry
                if (selectedResource != null) {
                    return selectedResource.resourceEntry.toStringValue(resourceTable, config, visited);
                }
                return null;
            } finally {
                // Remove the resourceId as we exit the branch to allow sibling paths to resolve it
                visited.remove(resourceId);
            }
        }

        private boolean isBetterThan(ResourceTable.Resource candidate, ResourceTable.Resource current, @Nullable DeviceConfig requestedConfig) {
            if (current == null) return true;

            if (requestedConfig != null && requestedConfig.mcc != 0) {
                if (candidate.type.config.getMcc() != current.type.config.getMcc()) {
                    return candidate.type.config.getMcc() != 0;
                }
                if (candidate.type.config.getMnc() != current.type.config.getMnc()) {
                    return candidate.type.config.getMnc() != 0;
                }
            } else {
                if (candidate.type.config.getMcc() != current.type.config.getMcc()) {
                    return candidate.type.config.getMcc() == 0;
                }
                if (candidate.type.config.getMnc() != current.type.config.getMnc()) {
                    return candidate.type.config.getMnc() == 0;
                }
            }

            if (candidate.type.config.getSdkVersion() != current.type.config.getSdkVersion()) {
                return candidate.type.config.getSdkVersion() > current.type.config.getSdkVersion();
            }

            if (requestedConfig != null && requestedConfig.uiMode != 0) {
                int reqNight = requestedConfig.uiMode & 0x30; // UI_MODE_NIGHT_MASK
                int candNight = candidate.type.config.getUiMode() & 0x30;
                int curNight = current.type.config.getUiMode() & 0x30;
                if (candNight != curNight) {
                    if (candNight == reqNight) return true;
                    if (curNight == reqNight) return false;
                }
            }

            if (requestedConfig != null && requestedConfig.density > 0) {
                int reqDensity = requestedConfig.density;
                int candDensity = candidate.type.density;
                int curDensity = current.type.density;

                if (candDensity != curDensity) {
                    if (candDensity == Densities.ANY) return true;
                    if (curDensity == Densities.ANY) return false;

                    int candidateDiff = Math.abs(candDensity - reqDensity);
                    int currentDiff = Math.abs(curDensity - reqDensity);
                    if (candidateDiff != currentDiff) {
                        return candidateDiff < currentDiff;
                    }
                }
            } else {
                int candidateDensity = densityLevel(candidate.type.density);
                int currentDensity = densityLevel(current.type.density);
                if (candidateDensity != currentDensity) {
                    return candidateDensity > currentDensity;
                }
            }

            return false;
        }

        public long getReferenceResourceId() {
            return this.value & 0xFFFFFFFFL;
        }

        private static int densityLevel(final int density) {
            if (density == Densities.ANY || density == Densities.NONE) {
                return -1;
            }
            return density;
        }
    }

    private static class NullResourceValue extends ResourceValue {
        private static final NullResourceValue instance = new NullResourceValue();

        private NullResourceValue() {
            super(-1);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return "";
        }
    }

    private static class RGBResourceValue extends ResourceValue {
        private final int len;

        private RGBResourceValue(final int value, final int len) {
            super(value);
            this.len = len;
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            final StringBuilder sb = new StringBuilder();
            sb.append("#");
            for (int i = this.len - 1; i >= 0; i--) {
                final int shift = i * 4;
                final int bits = (this.value >> shift) & 0xf;
                sb.append(Integer.toHexString(bits));
            }
            return sb.toString();
        }
    }

    private static final float[] RADIX_MULTS = new float[]{
            1.0f,
            1.0f / (1 << 7),
            1.0f / (1 << 15),
            1.0f / (1 << 23)
    };

    private static float complexToFloat(int complex) {
        return (complex >> 8) * RADIX_MULTS[(complex >> 4) & 3];
    }

    private static class DimensionValue extends ResourceValue {

        private DimensionValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            final short unit = (short) (this.value & 0xf);
            final String unitStr = switch (unit) {
                case ResValue.ResDataCOMPLEX.UNIT_MM -> "mm";
                case ResValue.ResDataCOMPLEX.UNIT_PX -> "px";
                case ResValue.ResDataCOMPLEX.UNIT_DIP -> "dp";
                case ResValue.ResDataCOMPLEX.UNIT_SP -> "sp";
                case ResValue.ResDataCOMPLEX.UNIT_PT -> "pt";
                case ResValue.ResDataCOMPLEX.UNIT_IN -> "in";
                default -> "unknown unit:0x" + Integer.toHexString(unit);
            };
            return complexToFloat(this.value) + unitStr;
        }
    }

    private static class FractionValue extends ResourceValue {

        private FractionValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            final short type = (short) (this.value & 0xf);
            final String pstr = switch (type) {
                case ResValue.ResDataCOMPLEX.UNIT_FRACTION -> "%";
                case ResValue.ResDataCOMPLEX.UNIT_FRACTION_PARENT -> "%p";
                default -> "unknown type:0x" + Integer.toHexString(type);
            };
            return (complexToFloat(this.value) * 100) + pstr;
        }
    }

    private static class RawValue extends ResourceValue {
        private final short dataType;

        private RawValue(final int value, final short dataType) {
            super(value);
            this.dataType = dataType;
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return "{" + this.dataType + ":" + (this.value & 0xFFFFFFFFL) + "}";
        }
    }

    public static class AttributeResourceValue extends ResourceValue {
        private AttributeResourceValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return "?" + Attribute.getString(this.value & 0xFFFFFFFFL);
        }
    }

    private static class FloatResourceValue extends ResourceValue {
        private FloatResourceValue(final int value) {
            super(value);
        }

        @NonNull
        @Override
        public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
            return String.valueOf(Float.intBitsToFloat(this.value));
        }
    }
}
