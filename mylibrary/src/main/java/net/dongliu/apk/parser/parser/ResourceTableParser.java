package net.dongliu.apk.parser.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.exception.ParserException;
import net.dongliu.apk.parser.struct.ChunkHeader;
import net.dongliu.apk.parser.struct.ChunkType;
import net.dongliu.apk.parser.struct.StringPool;
import net.dongliu.apk.parser.struct.StringPoolHeader;
import net.dongliu.apk.parser.struct.resource.LibraryEntry;
import net.dongliu.apk.parser.struct.resource.LibraryHeader;
import net.dongliu.apk.parser.struct.resource.NullHeader;
import net.dongliu.apk.parser.struct.resource.PackageHeader;
import net.dongliu.apk.parser.struct.resource.ResourcePackage;
import net.dongliu.apk.parser.struct.resource.ResourceTable;
import net.dongliu.apk.parser.struct.resource.ResourceTableHeader;
import net.dongliu.apk.parser.struct.resource.StagedAliasHeader;
import net.dongliu.apk.parser.struct.resource.Type;
import net.dongliu.apk.parser.struct.resource.TypeHeader;
import net.dongliu.apk.parser.struct.resource.TypeSpec;
import net.dongliu.apk.parser.struct.resource.TypeSpecHeader;
import net.dongliu.apk.parser.utils.Buffers;
import net.dongliu.apk.parser.utils.Pair;
import net.dongliu.apk.parser.utils.ParseUtils;
import net.dongliu.apk.parser.utils.Unsigned;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parse android resource table file.
 *
 * @author dongliu
 * @see <a href="https://github.com/aosp-mirror/platform_frameworks_base/blob/master/libs/androidfw/include/androidfw/ResourceTypes.h">ResourceTypes.h</a>
 * @see <a href="https://github.com/aosp-mirror/platform_frameworks_base/blob/master/libs/androidfw/ResourceTypes.cpp">ResourceTypes.cpp</a>
 */
public class ResourceTableParser {

    /**
     * By default the data buffer Chunks is buffer little-endian byte order both at runtime and when stored buffer files.
     */
    private final ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
    private StringPool stringPool;
    @NonNull
    private final ByteBuffer buffer;
    /**
     * the resource table file size
     */
    public ResourceTable resourceTable;
    @NonNull
    public final Set<Locale> locales;

    public ResourceTableParser(final @NonNull ByteBuffer buffer) {
        this.buffer = buffer.duplicate();
        this.buffer.order(this.byteOrder);
        this.locales = new HashSet<>();
    }

    /**
     * parse resource table file.
     */
    public void parse() {
        // read resource file header.
        final ResourceTableHeader resourceTableHeader = (ResourceTableHeader) this.readChunkHeader();
        // read string pool chunk
        final StringPool stringPool = ParseUtils.readStringPool(this.buffer, (StringPoolHeader) this.readChunkHeader());
        this.stringPool = stringPool;
        this.resourceTable = new ResourceTable(stringPool);
        final long packageCount = resourceTableHeader.getPackageCount();
        android.util.Log.d("AppLog", "icon fetching: packageCount in resources.arsc: " + packageCount);
        if (packageCount != 0) {
            PackageHeader packageHeader = (PackageHeader) this.readChunkHeader();
            for (int i = 0; i < packageCount; i++) {
                final Pair<ResourcePackage, PackageHeader> pair = this.readPackage(packageHeader);
                ResourcePackage resourcePackage = pair.getLeft();
                this.resourceTable.addPackage(resourcePackage);
                packageHeader = pair.getRight();
            }
        }
    }

    /**
     * read one package
     */
    private Pair<ResourcePackage, PackageHeader> readPackage(@NonNull final PackageHeader packageHeader) {
        final Pair<ResourcePackage, PackageHeader> pair = new Pair<>();
        //read packageHeader
        final ResourcePackage resourcePackage = new ResourcePackage(packageHeader);
        pair.setLeft(resourcePackage);
        final long beginPos = this.buffer.position();
        // read type string pool
        if (packageHeader.getTypeStrings() > 0) {
            Buffers.position(this.buffer, beginPos + packageHeader.getTypeStrings() - (int) packageHeader.headerSize);
            resourcePackage.setTypeStringPool(ParseUtils.readStringPool(this.buffer,
                    (StringPoolHeader) this.readChunkHeader()));
        }
        //read key string pool
        if (packageHeader.getKeyStrings() > 0) {
            Buffers.position(this.buffer, beginPos + packageHeader.getKeyStrings() - (int) packageHeader.headerSize);
            resourcePackage.setKeyStringPool(ParseUtils.readStringPool(this.buffer,
                    (StringPoolHeader) this.readChunkHeader()));
        }
        outer:
        while (this.buffer.hasRemaining()) {
            final ChunkHeader chunkHeader = this.readChunkHeader();
            final long chunkBegin = this.buffer.position();
            switch ((int) chunkHeader.chunkType) {
                case ChunkType.TABLE_TYPE_SPEC:
                    final TypeSpecHeader typeSpecHeader = (TypeSpecHeader) chunkHeader;
                    final long[] entryFlags = new long[typeSpecHeader.getEntryCount()];
                    for (int i = 0; i < typeSpecHeader.getEntryCount(); i++) {
                        entryFlags[i] = Buffers.readUInt(this.buffer);
                    }
                    //id start from 1
                    final String typeSpecName = resourcePackage.getTypeStringPool()
                            .get(typeSpecHeader.getId() - 1);
                    final TypeSpec typeSpec = new TypeSpec(typeSpecHeader, entryFlags, typeSpecName);
                    android.util.Log.d("AppLog", "icon fetching: in package 0x" + Integer.toHexString(resourcePackage.getId()) + ", adding typeSpec " + typeSpecName + " with ID 0x" + Integer.toHexString(typeSpecHeader.getId()) + " count: " + typeSpecHeader.getEntryCount());
                    resourcePackage.addTypeSpec(typeSpec);
                    Buffers.position(this.buffer, chunkBegin + typeSpecHeader.getBodySize());
                    break;
                case ChunkType.TABLE_TYPE:
                    final TypeHeader typeHeader = (TypeHeader) chunkHeader;
                    // read offsets table
                    int[] offsets = new int[typeHeader.entryCount];
                    int[] indices = null;
                    if ((typeHeader.getFlags() & 0x01) == 0x01) /* FLAG_SPARSE */ {
                        indices = new int[typeHeader.entryCount];
                        for (int i = 0; i < typeHeader.entryCount; i++) {
                            indices[i] = Unsigned.toInt(buffer.getShort());
                            int offset = Unsigned.toInt(buffer.getShort());
                            offsets[i] = (offset == 0xFFFF) ? -1 : offset * 4;
                        }
                    } else if ((typeHeader.getFlags() & 0x02) == 0x02) /* FLAG_OFFSET16 */ {
                        for (int i = 0; i < typeHeader.entryCount; i++) {
                            int offset = Unsigned.toInt(buffer.getShort());
                            offsets[i] = (offset == 0xFFFF) ? -1 : offset * 4;
                        }
                    } else {  // no flags
                        for (int i = 0; i < typeHeader.entryCount; i++) {
                            offsets[i] = buffer.getInt();
                        }
                    }
                    final Type type = new Type(typeHeader);
                    final String typeName = resourcePackage.getTypeStringPool().get(typeHeader.getId() - 1);
                    type.setName(typeName);
                    android.util.Log.d("AppLog", "icon fetching: in package 0x" + Integer.toHexString(resourcePackage.getId()) + ", adding type " + typeName + " with ID 0x" + Integer.toHexString(typeHeader.getId()) + " count: " + typeHeader.entryCount + " config: " + type.locale);
                    final long entryPos = chunkBegin + typeHeader.entriesStart - (int) typeHeader.headerSize;
                    Buffers.position(this.buffer, entryPos);
                    final ByteBuffer b = this.buffer.slice();
                    b.order(this.byteOrder);
                    type.setBuffer(b);
                    type.setKeyStringPool(resourcePackage.getKeyStringPool());
                    type.setOffsets(offsets, indices);
                    type.setStringPool(this.stringPool);
                    resourcePackage.addType(type);
                    this.locales.add(type.locale);
                    Buffers.position(this.buffer, chunkBegin + typeHeader.getBodySize());
                    break;
                case ChunkType.TABLE_PACKAGE:
                    // another package. we should read next package here
                    pair.setRight((PackageHeader) chunkHeader);
                    break outer;
                case ChunkType.TABLE_LIBRARY:
                    // read entries
                    final LibraryHeader libraryHeader = (LibraryHeader) chunkHeader;
                    for (long i = 0; i < libraryHeader.getCount(); i++) {
                        final int packageId = this.buffer.getInt();
                        final String name = Buffers.readZeroTerminatedString(this.buffer, 128);
                        android.util.Log.d("AppLog", "icon fetching: in package 0x" + Integer.toHexString(resourcePackage.getId()) + " (" + resourcePackage.getName() + "), found library mapping: 0x" + Integer.toHexString(packageId) + " -> " + name);
                        this.resourceTable.addLibraryMapping(packageId, name);
                        if (name.equals(resourcePackage.getName())) {
                            android.util.Log.d("AppLog", "icon fetching: remapping package " + name + " to ID 0x" + Integer.toHexString(packageId));
                            resourcePackage.setId((short) packageId);
                        }
                    }
                    Buffers.position(this.buffer, chunkBegin + chunkHeader.getBodySize());
                    break;
                case ChunkType.TABLE_STAGED_ALIAS:
                    final StagedAliasHeader stagedAliasHeader = (StagedAliasHeader) chunkHeader;
                    for (int i = 0; i < stagedAliasHeader.getCount(); i++) {
                        final int stagedResId = this.buffer.getInt();
                        final int finalizedResId = this.buffer.getInt();
                        resourcePackage.addStagedAlias(stagedResId, finalizedResId);
                    }
                    Buffers.position(this.buffer, chunkBegin + chunkHeader.getBodySize());
                    break;
                case ChunkType.TABLE_OVERLAYABLE:
                case ChunkType.NULL:
                    Buffers.position(this.buffer, chunkBegin + chunkHeader.getBodySize());
                    break;
                default:
                    throw new ParserException("unexpected chunk type: 0x" + (int) chunkHeader.chunkType);
            }
        }
        android.util.Log.d("AppLog", "icon fetching: finished reading package " + resourcePackage.getName() + ". typeSpecs: " + resourcePackage.getTypeSpecMap().size() + ", types: " + resourcePackage.getTypesMap().size());
        return pair;

    }

    @NonNull
    private ChunkHeader readChunkHeader() {
        final long begin = this.buffer.position();
        final int chunkType = Buffers.readUShort(this.buffer);
        final int headerSize = Buffers.readUShort(this.buffer);
        final int chunkSize = (int) Buffers.readUInt(this.buffer);
        switch (chunkType) {
            case ChunkType.TABLE: {
                final ResourceTableHeader resourceTableHeader = new ResourceTableHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return resourceTableHeader;
            }
            case ChunkType.STRING_POOL: {
                final StringPoolHeader stringPoolHeader = new StringPoolHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return stringPoolHeader;
            }
            case ChunkType.TABLE_PACKAGE: {
                final PackageHeader packageHeader = new PackageHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return packageHeader;
            }
            case ChunkType.TABLE_TYPE_SPEC: {
                final TypeSpecHeader typeSpecHeader = new TypeSpecHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return typeSpecHeader;
            }
            case ChunkType.TABLE_TYPE: {
                final TypeHeader typeHeader = new TypeHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return typeHeader;
            }
            case ChunkType.TABLE_LIBRARY: {
                //DynamicRefTable
                final LibraryHeader libraryHeader = new LibraryHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return libraryHeader;
            }
            case ChunkType.TABLE_STAGED_ALIAS: {
                final StagedAliasHeader stagedAliasHeader = new StagedAliasHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return stagedAliasHeader;
            }
            case ChunkType.TABLE_OVERLAYABLE:
            case ChunkType.NULL: {
                Buffers.position(this.buffer, begin + headerSize);
                return new NullHeader(chunkType, headerSize, chunkSize);
            }
            default:
                throw new ParserException("Unexpected chunk Type: 0x" + Integer.toHexString(chunkType));
        }
    }

    @Nullable
    public ResourceTable getResourceTable() {
        return this.resourceTable;
    }

}
