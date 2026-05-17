package com.lb.apkparserdemo.apk_info.zip

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Extension of [SeekableByteChannel] that supports memory mapping.
 */
interface MappableSeekableByteChannel : SeekableByteChannel {
    /**
     * Maps a region of this channel directly into memory.
     *
     * @param offset The offset within the channel where the mapped region is to start.
     * @param size The size of the region to be mapped.
     * @return The mapped byte buffer.
     */
    fun map(offset: Long, size: Long): ByteBuffer
}
