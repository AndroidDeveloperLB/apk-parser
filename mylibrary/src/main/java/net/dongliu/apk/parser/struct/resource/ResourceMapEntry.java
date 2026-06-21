package net.dongliu.apk.parser.struct.resource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.bean.DeviceConfig;
import net.dongliu.apk.parser.struct.ResourceValue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author dongliu.
 */
public class ResourceMapEntry extends ResourceEntry {
    /**
     * Resource identifier of the parent mapping, or 0 if there is none.
     * ResTable_ref specifies the parent Resource, if any, of this Resource.
     * struct ResTable_ref { uint32_t ident; };
     */
    public final long parent;

    /**
     * Number of name/value pairs that follow for FLAG_COMPLEX. uint32_t
     */
    public final long count;
    @NonNull
    public final ResourceTableMap[] resourceTableMaps;

    public ResourceMapEntry(final int size, final int flags, final String key, final long parent, final long count, final @NonNull ResourceTableMap[] resourceTableMaps) {
        super(size, flags, key, null);
        this.parent = parent;
        this.count = count;
        this.resourceTableMaps = resourceTableMaps;
    }

    /**
     * get value as string
     */
    @Nullable
    @Override
    public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config) {
        return toStringValue(resourceTable, config, new HashSet<>());
    }

    /**
     * get value as string, preventing loop cycles.
     */
    @Nullable
    @Override
    public String toStringValue(final ResourceTable resourceTable, @Nullable final DeviceConfig config, Set<Long> visited) {
        if (this.parent != 0 && resourceTable != null) {
//            android.util.Log.d("AppLog", "label fetching: ResourceMapEntry " + this.key + " follows parent alias 0x" + Long.toHexString(this.parent));
            String resolvedParent = ResourceValue.reference((int) this.parent).toStringValue(resourceTable, config, visited);
            if (resolvedParent != null && !resolvedParent.startsWith("resourceId:0x")) {
                return resolvedParent;
            }
        }

        for (ResourceTableMap map : this.resourceTableMaps) {
            final ResourceValue resValue = map.getResValue();
            if (resValue != null) {
                String val = resValue.toStringValue(resourceTable, config, visited);
                if (val != null) return val;
            }
            if (map.getData() != null) return map.getData();
        }
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "ResourceMapEntry{" +
                "parent=" + this.parent +
                ", count=" + this.count +
                ", resourceTableMaps=" + Arrays.toString(this.resourceTableMaps) +
                '}';
    }
}
