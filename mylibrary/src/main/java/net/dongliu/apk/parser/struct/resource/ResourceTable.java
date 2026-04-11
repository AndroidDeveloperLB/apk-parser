package net.dongliu.apk.parser.struct.resource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.struct.ResourceValue;
import net.dongliu.apk.parser.struct.StringPool;
import net.dongliu.apk.parser.utils.ResourceLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The apk resource table
 *
 * @author dongliu
 */
public class ResourceTable {
    private final Map<Short, ResourcePackage> packageMap = new HashMap<>();
    @Nullable
    public final StringPool stringPool;
    @NonNull
    public static final Map<Integer, String> sysStyle = ResourceLoader.loadSystemStyles();

    public ResourceTable(@Nullable final StringPool stringPool) {
        this.stringPool = stringPool;
    }

    public void addPackage(final @NonNull ResourcePackage resourcePackage) {
        android.util.Log.d("AppLog", "icon fetching: adding package " + resourcePackage.getName() + " with ID 0x" + Integer.toHexString(resourcePackage.getId()));
        this.packageMap.put(resourcePackage.getId(), resourcePackage);
    }

    public void merge(@NonNull ResourceTable other) {
        for (Map.Entry<Short, ResourcePackage> entry : other.packageMap.entrySet()) {
            ResourcePackage existing = this.packageMap.get(entry.getKey());
            if (existing == null) {
                this.packageMap.put(entry.getKey(), entry.getValue());
            } else {
                existing.merge(entry.getValue());
            }
        }
    }

    @Nullable
    public ResourcePackage getPackage(final short id) {
        ResourcePackage res = this.packageMap.get(id);
        if (res == null && id == 0x7f) {
            res = this.packageMap.get((short) 0);
            if (res != null) {
                android.util.Log.d("AppLog", "icon fetching: fallback for package ID 0x7f to ID 0 (package name: " + res.getName() + ")");
            }
        }
        return res;
    }

    /**
     * Get resources match the given resource id.
     */
    @NonNull
    public List<Resource> getResourcesById(final long resourceId) {
        // An Android Resource id is a 32-bit integer. It comprises
        // an 8-bit Package id [bits 24-31]
        // an 8-bit Type id [bits 16-23]
        // a 16-bit Entry index [bits 0-15]
        final short packageId = (short) (resourceId >> 24 & 0xff);
        final ResourcePackage resourcePackage = this.getPackage(packageId);
        if (resourcePackage == null) {
            StringBuilder sb = new StringBuilder();
            for (Short id : packageMap.keySet()) {
                sb.append("0x").append(Integer.toHexString(id)).append(" ");
            }
            android.util.Log.d("AppLog", "icon fetching: no package found for ID 0x" + Long.toHexString(resourceId) + " (packageId: 0x" + Integer.toHexString(packageId) + "). Available packages: " + sb.toString());
            return Collections.emptyList();
        }
        final int resolvedResourceId = resourcePackage.resolveStagedResId((int) resourceId);
        if (resolvedResourceId != (int) resourceId) {
            android.util.Log.d("AppLog", "icon fetching: resolved staged ID 0x" + Long.toHexString(resourceId) + " to 0x" + Integer.toHexString(resolvedResourceId));
            return getResourcesById(resolvedResourceId);
        }

        final short typeId = (short) ((resourceId >> 16) & 0xff);
        final int entryIndex = (int) (resourceId & 0xffff);
        final TypeSpec typeSpec = resourcePackage.getTypeSpec(typeId);
        final List<Type> types = resourcePackage.getTypes(typeId);
        if (typeSpec == null || types == null) {
            android.util.Log.d("AppLog", "icon fetching: no typeSpec or types found for ID 0x" + Long.toHexString(resourceId) + " (typeId: 0x" + Integer.toHexString(typeId) + ")");
            return Collections.emptyList();
        }
        if (!typeSpec.exists(entryIndex)) {
            android.util.Log.d("AppLog", "icon fetching: entry index 0x" + Integer.toHexString(entryIndex) + " does not exist in typeSpec for ID 0x" + Long.toHexString(resourceId) + " (typeSpec.entryFlags.length: " + typeSpec.entryFlags.length + ")");
            return Collections.emptyList();
        }
        // read from type resource
        final List<Resource> result = new ArrayList<>();
        for (final Type type : types) {
            final ResourceEntry resourceEntry = type.getResourceEntry(entryIndex);
            if (resourceEntry == null) {
                continue;
            }
            final ResourceValue currentResourceValue = resourceEntry.value;
            if (currentResourceValue == null) {
                continue;
            }
            // cyclic reference detect
            if (currentResourceValue instanceof ResourceValue.ReferenceResourceValue) {
                if (resourceId == ((ResourceValue.ReferenceResourceValue) currentResourceValue)
                        .getReferenceResourceId()) {
                    continue;
                }
            }
            result.add(new Resource(typeSpec, type, resourceEntry));
        }
        return result;
    }

    /**
     * contains all info for one resource
     */
    public static class Resource {
        @Nullable
        public final TypeSpec typeSpec;
        @NonNull
        public final Type type;
        @NonNull
        public final ResourceEntry resourceEntry;

        public Resource(final @Nullable TypeSpec typeSpec, final @NonNull Type type, final @NonNull ResourceEntry resourceEntry) {
            this.typeSpec = typeSpec;
            this.type = type;
            this.resourceEntry = resourceEntry;
        }

    }
}
