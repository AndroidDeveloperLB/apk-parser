package com.lb.apkparserdemo.apk_info.zip

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

/**
 * A [SeekableByteChannel] and [MappableSeekableByteChannel] implementation that operates on a [File].
 * Supports providing an optional [offset] and [size] to represent a nested file within a larger container.
 * Uses [FileChannel] for efficient native seeking and memory mapping.
 *
 * @param file The file to read from.
 * @param offset The starting position within the file (defaults to 0).
 * @param size The number of bytes to expose (defaults to file length minus offset).
 */
class FileSeekableByteChannel(
        file: File,
        private val offset: Long = 0L,
        size: Long = -1L
) : MappableSeekableByteChannel {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    private var position: Long = 0L
    private val actualSize: Long = if (size >= 0L) size else (file.length() - offset)

    override fun isOpen(): Boolean = channel.isOpen

    override fun close() {
        raf.close()
    }

    override fun read(dst: ByteBuffer): Int {
        if (position >= actualSize) return -1
        val toRead = minOf(dst.remaining().toLong(), actualSize - position).toInt()
        val oldLimit = dst.limit()
        dst.limit(dst.position() + toRead)

        channel.position(offset + position)
        val read = channel.read(dst)
        dst.limit(oldLimit)

        if (read > 0) position += read
        return read
    }

    override fun write(src: ByteBuffer?): Int = throw UnsupportedOperationException("Read-only")

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0)
        position = newPosition
        return this
    }

    override fun size(): Long = actualSize

    override fun map(offset: Long, size: Long): ByteBuffer =
            channel.map(FileChannel.MapMode.READ_ONLY, this.offset + offset, size)

    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("Read-only")
}
