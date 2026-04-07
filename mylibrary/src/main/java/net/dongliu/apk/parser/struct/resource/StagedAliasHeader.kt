package net.dongliu.apk.parser.struct.resource

import net.dongliu.apk.parser.struct.ChunkHeader
import net.dongliu.apk.parser.struct.ChunkType
import net.dongliu.apk.parser.utils.Buffers
import net.dongliu.apk.parser.utils.Unsigned.ensureUInt
import java.nio.ByteBuffer

/**
 * A chunk of data that contains mapping from staged resource IDs to finalized resource IDs.
 */
class StagedAliasHeader(headerSize: Int, chunkSize: Long, buffer: ByteBuffer) :
    ChunkHeader(ChunkType.TABLE_STAGED_ALIAS, headerSize, chunkSize) {
    /**
     * uint32 value, The number of staged alias entries that follow this header.
     */
    val count: Int

    init {
        count = ensureUInt(Buffers.readUInt(buffer))
    }
}
