package net.dongliu.apk.parser.struct

import net.dongliu.apk.parser.struct.ChunkType
import net.dongliu.apk.parser.utils.Unsigned

/**
 * String pool chunk header.
 *
 * @author dongliu
 */
class StringPoolHeader(headerSize: Int, chunkSize: Long) :
    ChunkHeader(ChunkType.STRING_POOL, headerSize, chunkSize) {
    /**
     * Number of style span arrays in the pool (number of uint32_t indices
     * follow the string indices).
     */
    var stringCount = 0
        private set

    /**
     * Number of style span arrays in the pool (number of uint32_t indices
     * follow the string indices).
     */
    var styleCount = 0
        private set
    var flags: Long = 0

    /**
     * Index from header of the string data.
     */
    var stringsStart: Long = 0

    /**
     * Index from header of the style data.
     */
    var stylesStart: Long = 0
    fun setStringCount(stringCount: Long) {
        this.stringCount = Unsigned.ensureUInt(stringCount)
    }

    fun setStyleCount(styleCount: Long) {
        this.styleCount = Unsigned.ensureUInt(styleCount)
    }

    companion object {
        /**
         * If set, the string index is sorted by the string values (based on strcmp16()).
         */
        const val SORTED_FLAG = 1

        /**
         * String pool is encoded in UTF-8
         */
        const val UTF8_FLAG = 1 shl 8
    }
}
