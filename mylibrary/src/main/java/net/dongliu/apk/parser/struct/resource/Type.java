package net.dongliu.apk.parser.struct.resource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.struct.ResourceValue;
import net.dongliu.apk.parser.struct.StringPool;
import net.dongliu.apk.parser.utils.Buffers;
import net.dongliu.apk.parser.utils.ParseUtils;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * @author dongliu
 */
public class Type {
    private String name;
    public final short id;

    @NonNull
    public final Locale locale;

    private StringPool keyStringPool;
    private ByteBuffer buffer;
    private long[] offsets;
    private StringPool stringPool;

    /**
     * see Densities.java for values
     */
    public final int density;

    public Type(final @NonNull TypeHeader header) {
        this.id = header.getId();
        final ResTableConfig config = header.config;
        this.locale = new Locale(config.getLanguage(), config.getCountry());
        this.density = config.getDensity();
    }

    @Nullable
    public ResourceEntry getResourceEntry(final int id) {
        if (id >= this.offsets.length) {
            return null;
        }
        if (this.offsets[id] == TypeHeader.NO_ENTRY) {
            return null;
        }
        // read Resource Entries
        Buffers.position(this.buffer, this.offsets[id]);
        return this.readResourceEntry();
    }

    private ResourceEntry readResourceEntry() {
        final long beginPos = this.buffer.position();
        // size is always 8(simple), or 16(complex)
        final int resourceEntrySize = Buffers.readUShort(this.buffer);
        final int resourceEntryFlags = Buffers.readUShort(this.buffer);
        final long keyRef = this.buffer.getInt();
        final String resourceEntryKey = this.keyStringPool.get((int) keyRef);
        if ((resourceEntryFlags & ResourceEntry.FLAG_COMPLEX) != 0) {
            // Resource identifier of the parent mapping, or 0 if there is none.
            final long parent = Buffers.readUInt(this.buffer);
            final long count = Buffers.readUInt(this.buffer);
//            resourceMapEntry.setParent(parent);
//            resourceMapEntry.setCount(count);
            Buffers.position(this.buffer, beginPos + resourceEntrySize);
            //An individual complex Resource entry comprises an entry immediately followed by one or more fields.
            final ResourceTableMap[] resourceTableMaps = new ResourceTableMap[(int) count];
            for (int i = 0; i < count; i++) {
                resourceTableMaps[i] = this.readResourceTableMap();
            }
            return new ResourceMapEntry(resourceEntrySize, resourceEntryFlags, resourceEntryKey, parent, count, resourceTableMaps);
        } else {
            Buffers.position(this.buffer, beginPos + resourceEntrySize);
            final ResourceValue resourceEntryValue = ParseUtils.readResValue(this.buffer, this.stringPool);
            return new ResourceEntry(resourceEntrySize, resourceEntryFlags, resourceEntryKey, resourceEntryValue);
        }
    }

    private ResourceTableMap readResourceTableMap() {
        final ResourceTableMap resourceTableMap = new ResourceTableMap();
        resourceTableMap.setNameRef(Buffers.readUInt(this.buffer));
        resourceTableMap.setResValue(ParseUtils.readResValue(this.buffer, this.stringPool));
        //noinspection StatementWithEmptyBody
        if ((resourceTableMap.getNameRef() & 0x02000000) != 0) {
            //read arrays
        } else //noinspection StatementWithEmptyBody
            if ((resourceTableMap.getNameRef() & 0x01000000) != 0) {
                // read attrs
            } else {
            }
        return resourceTableMap;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public StringPool getKeyStringPool() {
        return this.keyStringPool;
    }

    public void setKeyStringPool(final StringPool keyStringPool) {
        this.keyStringPool = keyStringPool;
    }

    public ByteBuffer getBuffer() {
        return this.buffer;
    }

    public void setBuffer(final ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public void setOffsets(final long[] offsets) {
        this.offsets = offsets;
    }

    public void setStringPool(final StringPool stringPool) {
        this.stringPool = stringPool;
    }

    @NonNull
    @Override
    public String toString() {
        return "Type{" +
                "name='" + this.name + '\'' +
                ", id=" + this.id +
                ", locale=" + this.locale +
                '}';
    }
}
