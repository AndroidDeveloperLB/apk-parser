package com.lb.apkparserdemo.apk_info.zip

import com.lb.common_utils.closeSilently
import com.lb.common_utils.readBytesIntoByteArray
import com.lb.common_utils.skipForcibly
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * A [SeekableByteChannel] implementation that wraps an [InputStream].
 * Since standard [InputStream]s are not seekable, this class provides seekability by
 * either skipping forward or restarting the stream and skipping from the beginning.
 *
 * This is useful when you have a way to recreate the stream (e.g., from a Uri or File),
 * allowing the parser to treat it as a seekable source.
 *
 * @param providedSize The total size of the stream, if known.
 */
abstract class SeekableInputStreamByteChannel(private val providedSize: Long = -1L) : MappableSeekableByteChannel {
    private var position: Long = 0L
    private var actualPosition: Long = 0L
    private var cachedSize: Long = providedSize
    private var inputStream: InputStream? = null
    private var buffer = ByteArray(DEFAULT_BUFFER_SIZE)

    /** Creates and returns a fresh [InputStream] positioned at the start. */
    abstract fun getNewInputStream(): InputStream

    override fun map(offset: Long, size: Long): ByteBuffer {
        val buffer = try {
            ByteBuffer.allocateDirect(size.toInt())
        } catch (_: Throwable) {
            System.gc()
            try {
                ByteBuffer.allocateDirect(size.toInt())
            } catch (_: Throwable) {
                // Last resort: allocate on heap
                ByteBuffer.allocate(size.toInt())
            }
        }
        val currentPosition = position
        position(offset)
        var remaining = size.toInt()
        while (remaining > 0) {
            val read = read(buffer)
            if (read == -1) break
            remaining -= read
        }
        position(currentPosition)
        buffer.flip()
        return buffer
    }

    override fun isOpen(): Boolean = true

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
//        Log.d("AppLog", "position $newPosition")
        require(newPosition >= 0L) { "Position has to be positive" }
        position = newPosition
        return this
    }

    open fun calculateSize(): Long {
        if (providedSize >= 0L) return providedSize
        return getNewInputStream().use { inputStream: InputStream ->
            if (inputStream is FileInputStream)
                return inputStream.channel.size()
            inputStream.skipForcibly(Long.MAX_VALUE)
        }
    }

    final override fun size(): Long {
        if (cachedSize < 0L)
            cachedSize = calculateSize()
//        Log.d("AppLog", "size $cachedSize")
        return cachedSize
    }

    override fun close() {
        inputStream.closeSilently().also { inputStream = null }
    }

    override fun read(buf: ByteBuffer): Int {
        var wanted: Int = buf.remaining()
//        Log.d("AppLog", "read wanted:$wanted")
        if (wanted <= 0)
            return wanted
        val possible = (size() - position).toInt()
        if (possible <= 0)
            return -1
        if (wanted > possible)
            wanted = possible
        if (buffer.size < wanted)
            buffer = ByteArray(wanted)
//        inputStream?.close()
//        inputStream=null
        var inputStream = this.inputStream
        //skipping to required position
        if (inputStream == null) {
            inputStream = getNewInputStream()
//            Log.d("AppLog", "getNewInputStream")
            inputStream.skipForcibly(position)
            this.inputStream = inputStream
        } else {
            if (actualPosition > position) {
                inputStream.close()
                actualPosition = 0L
                inputStream = getNewInputStream()
//                Log.d("AppLog", "getNewInputStream")
                this.inputStream = inputStream
            }
            inputStream.skipForcibly(position - actualPosition)
        }
        //now we have an inputStream right on the needed position
        inputStream.readBytesIntoByteArray(buffer, wanted)
        buf.put(buffer, 0, wanted)
        position += wanted.toLong()
        actualPosition = position
        return wanted
    }

    //not needed, because we don't store anything in memory
    override fun truncate(size: Long): SeekableByteChannel = this

    override fun write(src: ByteBuffer?): Int {
        //not needed, we read only
        throw NotImplementedError()
    }
}
