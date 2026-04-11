package net.dongliu.apk.parser.struct.resource;

import androidx.annotation.NonNull;

import net.dongliu.apk.parser.struct.ChunkHeader;
import net.dongliu.apk.parser.struct.ChunkType;
import net.dongliu.apk.parser.utils.Buffers;
import net.dongliu.apk.parser.utils.Unsigned;

import java.nio.ByteBuffer;

/**
 * @author dongliu
 */
public class TypeHeader extends ChunkHeader {

    public static final long NO_ENTRY = 0xFFFFFFFFL;

    /**
     * The type identifier this chunk is holding.  Type IDs start at 1 (corresponding to the value
     * of the type bits in a resource identifier).  0 is invalid.
     * uint8_t
     */
    private final byte id;

    /**
     * Must be 0. uint8_t
     */
    private final byte flags;
    /**
     * Must be 0. uint16_t
     */
    private final short res;

    /**
     * Number of uint32_t entry indices that follow. uint32
     */
    public final int entryCount;

    /**
     * Offset from header where ResTable_entry data starts.uint32_t
     */
    public final int entriesStart;

    /**
     * Configuration this collection of entries is designed for.
     */
    @NonNull
    public final ResTableConfig config;

    public TypeHeader(final int headerSize, final long chunkSize, @NonNull final ByteBuffer buffer) {
        super(ChunkType.TABLE_TYPE, headerSize, chunkSize);
        this.id = Unsigned.toUByte(Buffers.readUByte(buffer));
        this.flags = Unsigned.toUByte(Buffers.readUByte(buffer));
        this.res = Unsigned.toUShort(Buffers.readUShort(buffer));
        this.entryCount = Unsigned.ensureUInt(Buffers.readUInt(buffer));
        this.entriesStart = Unsigned.ensureUInt(Buffers.readUInt(buffer));
        this.config = this.readResTableConfig(buffer);
    }

    public short getId() {
        return Unsigned.toShort(this.id);
    }

    public short getFlags() {
        return Unsigned.toUShort(this.flags);
    }

    public int getRes() {
        return Unsigned.toInt(this.res);
    }

    @NonNull
    private ResTableConfig readResTableConfig(final ByteBuffer buffer) {
        final long beginPos = buffer.position();
        final ResTableConfig config = new ResTableConfig();
        final int size = (int) Buffers.readUInt(buffer);
        config.setSize(size);
        
        // imsi
        config.setMcc(buffer.getShort());
        config.setMnc(buffer.getShort());
        
        // locale
        config.setLanguage(readLocaleCode(buffer));
        config.setCountry(readLocaleCode(buffer));
        
        // screen type
        config.setOrientation(Buffers.readUByte(buffer));
        config.setTouchscreen(Buffers.readUByte(buffer));
        config.setDensity(Buffers.readUShort(buffer));
        
        // input
        if (size >= 32) {
            config.setKeyboard(Buffers.readUByte(buffer));
            config.setNavigation(Buffers.readUByte(buffer));
            config.setInputFlags(Buffers.readUByte(buffer));
            buffer.get(); // pad
        }
        
        // screen size
        if (size >= 36) {
            config.setScreenWidth(Buffers.readUShort(buffer));
            config.setScreenHeight(Buffers.readUShort(buffer));
        }
        
        // version
        if (size >= 40) {
            config.setSdkVersion(Buffers.readUShort(buffer));
            config.setMinorVersion(Buffers.readUShort(buffer));
        }
        
        // screen config
        if (size >= 48) {
            config.setScreenLayout((short) Buffers.readUByte(buffer));
            config.setUiMode((short) Buffers.readUByte(buffer));
            config.setScreenLayout2((short) Buffers.readUShort(buffer));
        }
        
        // locale extensions
        if (size >= 52) {
            byte[] script = Buffers.readBytes(buffer, 4);
            config.setScript(new String(script).replace("\0", ""));
        }
        
        if (size >= 60) {
            byte[] variant = Buffers.readBytes(buffer, 8);
            config.setVariant(new String(variant).replace("\0", ""));
        }

        final long endPos = buffer.position();
        Buffers.skip(buffer, (int) (size - (endPos - beginPos)));
        return config;
    }

    private String readLocaleCode(ByteBuffer buffer) {
        byte b1 = buffer.get();
        byte b2 = buffer.get();
        if ((b1 & 0x80) != 0) {
            // 3-letter code
            char[] chars = new char[3];
            chars[0] = (char) (0x61 + (b2 & 0x1f));
            chars[1] = (char) (0x61 + ((b2 & 0xe0) >> 5) + ((b1 & 0x03) << 3));
            chars[2] = (char) (0x61 + ((b1 & 0x7c) >> 2));
            return new String(chars);
        } else {
            if (b1 == 0) return "";
            if (b2 == 0) return new String(new char[]{(char) b1});
            return new String(new char[]{(char) b1, (char) b2});
        }
    }
}
