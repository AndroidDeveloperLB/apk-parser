package net.dongliu.apk.parser.struct.resource;

import net.dongliu.apk.parser.struct.ChunkHeader;
import net.dongliu.apk.parser.struct.ChunkType;
import net.dongliu.apk.parser.utils.Unsigned;

/**
 * Table library chunk header
 *
 * @author Liu Dong
 */
public class LibraryHeader extends ChunkHeader {
    /**
     * uint32 value, The number of shared libraries linked in this resource table.
     */
    private int count;

    public LibraryHeader(final int headerSize, final long chunkSize) {
        super(ChunkType.TABLE_LIBRARY, headerSize, chunkSize);
    }

    public int getCount() {
        return this.count;
    }

    public void setCount(final long count) {
        this.count = Unsigned.ensureUInt(count);
    }
}
