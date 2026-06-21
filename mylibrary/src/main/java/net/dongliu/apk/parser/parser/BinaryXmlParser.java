package net.dongliu.apk.parser.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.dongliu.apk.parser.bean.DeviceConfig;
import net.dongliu.apk.parser.exception.ParserException;
import net.dongliu.apk.parser.struct.ChunkHeader;
import net.dongliu.apk.parser.struct.ChunkType;
import net.dongliu.apk.parser.struct.ResourceValue;
import net.dongliu.apk.parser.struct.StringPool;
import net.dongliu.apk.parser.struct.StringPoolHeader;
import net.dongliu.apk.parser.struct.resource.ResourceTable;
import net.dongliu.apk.parser.struct.xml.Attribute;
import net.dongliu.apk.parser.struct.xml.Attributes;
import net.dongliu.apk.parser.struct.xml.NullHeader;
import net.dongliu.apk.parser.struct.xml.XmlCData;
import net.dongliu.apk.parser.struct.xml.XmlHeader;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag;
import net.dongliu.apk.parser.struct.xml.XmlNodeHeader;
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag;
import net.dongliu.apk.parser.struct.xml.XmlResourceMapHeader;
import net.dongliu.apk.parser.utils.Buffers;
import net.dongliu.apk.parser.utils.ParseUtils;
import net.dongliu.apk.parser.utils.Strings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Android Binary XML format
 * see http://justanapplication.wordpress.com/category/android/android-binary-xml/
 *
 * @author dongliu
 */
public class BinaryXmlParser {

    /**
     * By default, the data buffer Chunks is buffer little-endian byte order both at runtime and when stored buffer
     * files.
     */
    private StringPool stringPool;
    /**
     * some attribute name stored by resource id
     */
    private String[] resourceMap;
    @NonNull
    private final ByteBuffer buffer;
    @NonNull
    private final XmlStreamer xmlStreamer;
    @NonNull
    private final ResourceTable resourceTable;
    /**
     * preferred config.
     */
    @Nullable
    private final DeviceConfig config;
    @Nullable
    private final Locale locale;

    public BinaryXmlParser(final @NonNull ByteBuffer buffer, final @NonNull ResourceTable resourceTable, final @NonNull XmlStreamer xmlStreamer
            , final @Nullable DeviceConfig config) {
        this.buffer = buffer.duplicate();
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
        this.resourceTable = resourceTable;
        this.xmlStreamer = xmlStreamer;
        this.config = config;
        this.locale = config != null ? config.locale : null;
    }

    public BinaryXmlParser(final @NonNull ByteBuffer buffer, final @NonNull ResourceTable resourceTable, final @NonNull XmlStreamer xmlStreamer
            , final @Nullable Locale locale) {
        this(buffer, resourceTable, xmlStreamer, locale != null ? DeviceConfig.defaultLocale(locale) : null);
    }

    public void parse() {
        ChunkHeader firstChunkHeader = this.readChunkHeader();
        if (firstChunkHeader == null) {
            return;
        }

        if (firstChunkHeader.chunkType == ChunkType.XML) {
            firstChunkHeader = this.readChunkHeader();
        }

        if (firstChunkHeader == null) {
            return;
        }

        // read string pool chunk
        final ChunkHeader stringPoolChunkHeader = firstChunkHeader;
        if (stringPoolChunkHeader.chunkType != ChunkType.STRING_POOL) {
            throw new ParserException("Expected string pool chunk, but found: " + stringPoolChunkHeader.chunkType);
        }
        this.stringPool = ParseUtils.readStringPool(this.buffer, (StringPoolHeader) stringPoolChunkHeader);
        // read on chunk, check if it was an optional XMLResourceMap chunk
        ChunkHeader chunkHeader = this.readChunkHeader();
        if (chunkHeader == null) {
            return;
        }
        if (chunkHeader.chunkType == ChunkType.XML_RESOURCE_MAP) {
            final long[] resourceIds = this.readXmlResourceMap((XmlResourceMapHeader) chunkHeader);
            this.resourceMap = new String[resourceIds.length];
            for (int i = 0; i < resourceIds.length; i++) {
                this.resourceMap[i] = Attribute.getString(resourceIds[i]);
            }
            chunkHeader = this.readChunkHeader();
        }
        while (chunkHeader != null) {
            final long beginPos = this.buffer.position();
            switch ((int) chunkHeader.chunkType) {
                case ChunkType.XML_END_NAMESPACE:
                    final XmlNamespaceEndTag xmlNamespaceEndTag = this.readXmlNamespaceEndTag();
                    this.xmlStreamer.onNamespaceEnd(xmlNamespaceEndTag);
                    break;
                case ChunkType.XML_START_NAMESPACE:
                    final XmlNamespaceStartTag namespaceStartTag = this.readXmlNamespaceStartTag();
                    this.xmlStreamer.onNamespaceStart(namespaceStartTag);
                    break;
                case ChunkType.XML_START_ELEMENT:
                    final XmlNodeStartTag xmlNodeStartTag = this.readXmlNodeStartTag();
                    break;
                case ChunkType.XML_END_ELEMENT:
                    final XmlNodeEndTag xmlNodeEndTag = this.readXmlNodeEndTag();
                    break;
                case ChunkType.XML_CDATA:
                    final XmlCData xmlCData = this.readXmlCData();
                    break;
                default:
                    if ((int) chunkHeader.chunkType >= ChunkType.XML_FIRST_CHUNK &&
                            (int) chunkHeader.chunkType <= ChunkType.XML_LAST_CHUNK) {
                        Buffers.skip(this.buffer, chunkHeader.getBodySize());
                    } else {
                        throw new ParserException("Unexpected chunk type:" + (int) chunkHeader.chunkType);
                    }
            }
            long nextPos = beginPos + chunkHeader.getBodySize();
            if (nextPos > this.buffer.limit() || nextPos < 0) {
//                android.util.Log.w("AppLog", "label fetching: invalid chunk size " + chunkHeader.getChunkSize() + " at " + beginPos + ". Limit: " + this.buffer.limit());
                break;
            }
            Buffers.position(this.buffer, nextPos);
            chunkHeader = this.readChunkHeader();
        }
    }

    private XmlCData readXmlCData() {
        final XmlCData xmlCData = new XmlCData();
        final int dataRef = this.buffer.getInt();
        if (dataRef > 0) {
            xmlCData.setData(this.stringPool.get(dataRef));
        }
        xmlCData.setTypedData(ParseUtils.readResValue(this.buffer, this.stringPool));
        //TODO: to know more about cdata. some cdata appears buffer xml tags
//            String value = xmlCData.toStringValue(resourceTable, locale);
//            xmlCData.setValue(value);
//            xmlStreamer.onCData(xmlCData);
        return xmlCData;
    }

    private XmlNodeEndTag readXmlNodeEndTag() {
        final XmlNodeEndTag xmlNodeEndTag = new XmlNodeEndTag();
        final int nsRef = this.buffer.getInt();
        final int nameRef = this.buffer.getInt();
        if (nsRef > 0) {
            xmlNodeEndTag.setNamespace(this.stringPool.get(nsRef));
        }
        xmlNodeEndTag.setName(this.stringPool.get(nameRef));
        this.xmlStreamer.onEndTag(xmlNodeEndTag);
        return xmlNodeEndTag;
    }

    private XmlNodeStartTag readXmlNodeStartTag() {
        final int nsRef = this.buffer.getInt();
        final int nameRef = this.buffer.getInt();
        final String namespace = nsRef > 0 ? this.stringPool.get(nsRef) : null;
        final String name = this.stringPool.get(nameRef);
        // read attributes.
        // attributeStart and attributeSize are always 20 (0x14)
        final int attributeStart = Buffers.readUShort(this.buffer);
        final int attributeSize = Buffers.readUShort(this.buffer);
        final int attributeCount = Buffers.readUShort(this.buffer);
        final int idIndex = Buffers.readUShort(this.buffer);
        final int classIndex = Buffers.readUShort(this.buffer);
        final int styleIndex = Buffers.readUShort(this.buffer);
        // read attributes
        final Attributes attributes = new Attributes(attributeCount);
        for (int count = 0; count < attributeCount; count++) {
            final Attribute attribute = this.readAttribute();
            final String attributeName = attribute.name;
            String value = attribute.toStringValue(this.resourceTable, this.config);
            if (value != null && BinaryXmlParser.intAttributes.contains(attributeName) && Strings.isNumeric(value)) {
                try {
                    value = this.getFinalValueAsString(attributeName, value);
                } catch (final Exception ignore) {
                }
            }
            attribute.value = value;
            attributes.set(count, attribute);
        }
        final XmlNodeStartTag xmlNodeStartTag = new XmlNodeStartTag(namespace, name, attributes);
        this.xmlStreamer.onStartTag(xmlNodeStartTag);
        return xmlNodeStartTag;
    }

    private static final Set<String> intAttributes = new HashSet<>(
            Arrays.asList("screenOrientation", "configChanges", "windowSoftInputMode",
                    "launchMode", "installLocation", "protectionLevel"));

    /**
     * trans int attr value to string
     */
    private String getFinalValueAsString(final String attributeName, @NonNull final String str) {
        if (attributeName == null) {
            return str;
        }
        final int value = Integer.parseInt(str);
        return switch (attributeName) {
            case "screenOrientation" -> AttributeValues.getScreenOrientation(value);
            case "configChanges" -> AttributeValues.getConfigChanges(value);
            case "windowSoftInputMode" -> AttributeValues.getWindowSoftInputMode(value);
            case "launchMode" -> AttributeValues.getLaunchMode(value);
            case "installLocation" -> AttributeValues.getInstallLocation(value);
            case "protectionLevel" -> AttributeValues.getProtectionLevel(value);
            default -> str;
        };
    }

    private Attribute readAttribute() {
        final int namespaceRef = this.buffer.getInt();
        final int nameRef = this.buffer.getInt();
        String name = (this.stringPool != null && nameRef >= 0) ? this.stringPool.get(nameRef) : "";
        if (name == null) name = "";

        // Use resourceMap to resolve attribute names if possible.
        // This is crucial for APKs with obfuscated or shifted string pools.
        if (this.resourceMap != null && nameRef >= 0 && nameRef < this.resourceMap.length) {
            String mapName = this.resourceMap[nameRef];
            if (mapName != null && !mapName.isEmpty() && !mapName.startsWith("AttrId:0x")) {
                // If we resolved a known system name from the resource map, trust it over the string pool.
                name = mapName;
            }
        }

        String namespace = (this.stringPool != null && namespaceRef >= 0) ? this.stringPool.get(namespaceRef) : null;
        if (namespace == null || namespace.isEmpty() || "http://schemas.android.com/apk/res/android".equals(namespace)) {
            // Standardize platform namespace to "android" for easier lookup in Attributes.get().
            namespace = "android";
        }

        final int rawValueRef = this.buffer.getInt();
        final String rawValue = (this.stringPool != null && rawValueRef >= 0) ? this.stringPool.get(rawValueRef) : null;
        final ResourceValue resValue = ParseUtils.readResValue(this.buffer, this.stringPool);
        return new Attribute(namespace, name, rawValue, resValue);
    }

    @NonNull
    private XmlNamespaceStartTag readXmlNamespaceStartTag() {
        final int prefixRef = this.buffer.getInt();
        final int uriRef = this.buffer.getInt();
        final String prefix = prefixRef > 0 ? this.stringPool.get(prefixRef) : null;
        final String uri = uriRef > 0 ? this.stringPool.get(uriRef) : null;
        return new XmlNamespaceStartTag(prefix, uri);
    }

    @NonNull
    private XmlNamespaceEndTag readXmlNamespaceEndTag() {
        final int prefixRef = this.buffer.getInt();
        final String prefix = prefixRef <= 0 ? null : this.stringPool.get(prefixRef);
        final int uriRef = this.buffer.getInt();
        final String uri = uriRef <= 0 ? null : this.stringPool.get(uriRef);
        return new XmlNamespaceEndTag(prefix, uri);
    }

    private long[] readXmlResourceMap(final XmlResourceMapHeader chunkHeader) {
        final int count = chunkHeader.getBodySize() / 4;
        final long[] resourceIds = new long[count];
        for (int i = 0; i < count; i++) {
            resourceIds[i] = Buffers.readUInt(this.buffer);
        }
        return resourceIds;
    }

    @Nullable
    private ChunkHeader readChunkHeader() {
        // finished
        if (!this.buffer.hasRemaining()) {
            return null;
        }
        final long begin = this.buffer.position();
        final int chunkType = Buffers.readUShort(this.buffer);
        final int headerSize = Buffers.readUShort(this.buffer);
        final long chunkSize = Buffers.readUInt(this.buffer);
        switch (chunkType) {
            case ChunkType.XML:
                return new XmlHeader(chunkType, headerSize, chunkSize);
            case ChunkType.STRING_POOL:
                final StringPoolHeader stringPoolHeader = new StringPoolHeader(headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return stringPoolHeader;
            case ChunkType.XML_RESOURCE_MAP:
                Buffers.position(this.buffer, begin + headerSize);
                return new XmlResourceMapHeader(chunkType, headerSize, chunkSize);
            case ChunkType.XML_START_NAMESPACE:
            case ChunkType.XML_END_NAMESPACE:
            case ChunkType.XML_START_ELEMENT:
            case ChunkType.XML_END_ELEMENT:
            case ChunkType.XML_CDATA:
                final XmlNodeHeader header = new XmlNodeHeader(chunkType, headerSize, chunkSize, this.buffer);
                Buffers.position(this.buffer, begin + headerSize);
                return header;
            case ChunkType.NULL:
                return new NullHeader(chunkType, headerSize, chunkSize);
            default:
                throw new ParserException("Unexpected chunk type:" + chunkType);
        }
    }

    @Nullable
    public Locale getLocale() {
        return this.locale;
    }

}
