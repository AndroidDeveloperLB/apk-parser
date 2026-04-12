package net.dongliu.apk.parser.struct.resource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.struct.StringPool;
import net.dongliu.apk.parser.utils.ResourceLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resource table.
 *
 * @author dongliu
 */
public class ResourceTable {
    private final Map<Short, ResourcePackage> packageMap = new HashMap<>();
    private final Map<String, Short> nameToIdMap = new HashMap<>();
    public final StringPool stringPool;
    @NonNull
    public static final Map<Integer, String> sysStyle = ResourceLoader.loadSystemStyles();
    private final Set<Locale> locales = new HashSet<>();

    public ResourceTable(@Nullable final StringPool stringPool) {
        this.stringPool = stringPool;
    }

    public void addPackage(final @NonNull ResourcePackage resourcePackage) {
        ResourcePackage existing = this.packageMap.get(resourcePackage.getId());
        this.nameToIdMap.put(resourcePackage.getName(), resourcePackage.getId());
        if (existing == null) {
            android.util.Log.d("AppLog", "label fetching: adding new package " + resourcePackage.getName() + " with ID 0x" + Integer.toHexString(resourcePackage.getId()));
            this.packageMap.put(resourcePackage.getId(), resourcePackage);
        } else {
            android.util.Log.d("AppLog", "label fetching: merging existing package " + existing.getName() + " with " + resourcePackage.getName() + " for ID 0x" + Integer.toHexString(resourcePackage.getId()));
            existing.merge(resourcePackage);
        }
        android.util.Log.d("AppLog", "label fetching: package " + resourcePackage.getName() + " added with ID 0x" + Integer.toHexString(resourcePackage.getId()));
        updateLocales();
    }

    public void merge(@NonNull ResourceTable other) {
        this.nameToIdMap.putAll(other.nameToIdMap);
        for (Map.Entry<Short, ResourcePackage> entry : other.packageMap.entrySet()) {
            ResourcePackage existing = this.packageMap.get(entry.getKey());
            if (existing == null) {
                this.packageMap.put(entry.getKey(), entry.getValue());
            } else {
                existing.merge(entry.getValue());
            }
        }
        updateLocales();
    }

    private void updateLocales() {
        this.locales.clear();
        for (ResourcePackage p : packageMap.values()) {
            for (java.util.List<Type> types : p.getTypesMap().values()) {
                for (Type t : types) {
                    this.locales.add(t.locale);
                }
            }
        }
    }

    public Set<Locale> getLocales() {
        return locales;
    }

    public void addLibraryMapping(int id, String name) {
        this.nameToIdMap.put(name, (short) id);
    }

    public Map<Short, ResourcePackage> getPackageMap() {
        return packageMap;
    }

    public ResourcePackage getPackage(final short id) {
        ResourcePackage res = this.packageMap.get(id);
        if (res != null) {
            return res;
        }
        if (id == 0x7f) {
            res = this.packageMap.get((short) 0);
            if (res != null) {
                android.util.Log.d("AppLog", "label fetching: fallback for package ID 0x7f to ID 0 (package name: " + res.getName() + ")");
                return res;
            }
        }
        for (Map.Entry<String, Short> entry : nameToIdMap.entrySet()) {
            if (entry.getValue() == id) {
                ResourcePackage p = findPackageByName(entry.getKey());
                if (p != null) {
                    android.util.Log.d("AppLog", "label fetching: resolved package for ID 0x" + Integer.toHexString(id) + " via name mapping to " + p.getName() + " (actual ID: 0x" + Integer.toHexString(p.getId()) + ")");
                    return p;
                }
            }
        }
        if (packageMap.size() == 2 && packageMap.containsKey((short) 0x01)) {
            for (ResourcePackage p : packageMap.values()) {
                if (p.getId() != 0x01) {
                    android.util.Log.d("AppLog", "label fetching: absolute fallback for ID 0x" + Integer.toHexString(id) + " to package " + p.getName() + " (ID: 0x" + Integer.toHexString(p.getId()) + ")");
                    return p;
                }
            }
        }
        return null;
    }

    public ResourcePackage findPackageByName(String name) {
        for (ResourcePackage p : packageMap.values()) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public List<Resource> getResourcesById(final long resourceId) {
        return getResourcesById(resourceId, new HashSet<>());
    }

    @NonNull
    private List<Resource> getResourcesById(final long resourceId, Set<Long> visitedIds) {
        if (visitedIds.contains(resourceId)) {
            return Collections.emptyList();
        }
        visitedIds.add(resourceId);
        // An Android Resource id is a 32-bit integer. It comprises
        // an 8-bit Package id [bits 24-31]
        // an 8-bit Type id [bits 16-23]
        // a 16-bit Entry index [bits 0-15]
        final short packageId = (short) (resourceId >> 24 & 0xff);
        ResourcePackage resourcePackage = this.getPackage(packageId);
        if (resourcePackage == null) {
            StringBuilder sb = new StringBuilder();
            for (Short id : packageMap.keySet()) {
                sb.append("0x").append(Integer.toHexString(id)).append(" ");
            }
            android.util.Log.d("AppLog", "label fetching: no package found for ID 0x" + Long.toHexString(resourceId) + " (packageId: 0x" + Integer.toHexString(packageId) + "). Available packages: " + sb.toString());
            return Collections.emptyList();
        }
        final int resolvedResourceId = resourcePackage.resolveStagedResId((int) resourceId);
        if (resolvedResourceId != (int) resourceId) {
            android.util.Log.d("AppLog", "label fetching: resolved staged ID 0x" + Long.toHexString(resourceId) + " to 0x" + Integer.toHexString(resolvedResourceId));
            return getResourcesById(resolvedResourceId, visitedIds);
        }

        final short typeId = (short) ((resourceId >> 16) & 0xff);
        final int entryIndex = (int) (resourceId & 0xffff);
        final TypeSpec typeSpec = resourcePackage.getTypeSpec(typeId);
        final List<Type> types = resourcePackage.getTypes(typeId);
        if (typeSpec == null || types == null) {
            android.util.Log.d("AppLog", "label fetching: no typeSpec or types found for ID 0x" + Long.toHexString(resourceId) + " (typeId: 0x" + Integer.toHexString(typeId) + ")");
            return Collections.emptyList();
        }
        if (!typeSpec.exists(entryIndex)) {
            android.util.Log.d("AppLog", "label fetching: entry index 0x" + Integer.toHexString(entryIndex) + " does not exist in typeSpec for ID 0x" + Long.toHexString(resourceId) + " (typeSpec.entryFlags.length: " + typeSpec.entryFlags.length + ")");
            return Collections.emptyList();
        }
        List<Resource> resources = new ArrayList<>();
        for (Type type : types) {
            ResourceEntry resourceEntry = type.getResourceEntry(entryIndex);
            if (resourceEntry != null) {
                resources.add(new Resource(typeSpec, type, resourceEntry));
            }
        }
        return resources;
    }

    /**
     * contains all info for one resource
     */
    public static class Resource {
        public final TypeSpec typeSpec;
        public final Type type;
        public final ResourceEntry resourceEntry;

        public Resource(TypeSpec typeSpec, Type type, ResourceEntry resourceEntry) {
            this.typeSpec = typeSpec;
            this.type = type;
            this.resourceEntry = resourceEntry;
        }
    }
}
