package com.lb.apkparserdemo.apk_info.zip

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

/**
 * A [SeekableByteChannel] that wraps another channel and exposes only a specific sub-range (start to start + size).
 * Useful for treating an entry within a zip file as a standalone channel.
 *
 * @param channel The parent channel.
 * @param start The absolute start position in the parent channel.
 * @param size The number of bytes to expose.
 */
class BoundedSeekableByteChannel(
        private val channel: SeekableByteChannel,
        private val start: Long,
        private val size: Long
) : MappableSeekableByteChannel {
    private var position: Long = 0L

    override fun isOpen(): Boolean = channel.isOpen
    override fun close() {} // Do not close parent channel

    override fun read(dst: ByteBuffer): Int {
        if (position >= size) return -1
        val remaining = size - position
        val oldLimit = dst.limit()
        if (dst.remaining() > remaining) {
            dst.limit(dst.position() + remaining.toInt())
        }
        channel.position(start + position)
        val n = channel.read(dst)
        dst.limit(oldLimit)
        if (n > 0) position += n
        return n
    }

    override fun write(src: ByteBuffer?): Int = throw UnsupportedOperationException()
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel {
        position = newPosition
        return this
    }

    override fun size(): Long = size
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()

    override fun map(offset: Long, size: Long): ByteBuffer {
        if (channel is FileChannel) {
            return channel.map(FileChannel.MapMode.READ_ONLY, start + offset, size)
        }
        if (channel is MappableSeekableByteChannel) {
            return channel.map(start + offset, size)
        }
        throw UnsupportedOperationException("Underlying channel does not support mapping")
    }
}
