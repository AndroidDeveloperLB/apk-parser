package com.lb.apkparserdemo.apk_info

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.min


/**
 * Reads the stream into a direct [ByteBuffer], checking for available native memory first.
 *
 * @param context Android context for memory checks.
 * @param size Number of bytes to read.
 * @return A direct [ByteBuffer] containing the stream's contents, or null if OOM or failure.
 */
fun InputStream.readIntoDirectBuffer(context: Context, size: Int): ByteBuffer? {
    if (!MemoryUtils.isEnoughNativeMemory(context, size.toLong())) {
        System.gc()
        if (!MemoryUtils.isEnoughNativeMemory(context, size.toLong()))
            return null
    }
    val buffer: ByteBuffer? = try {
        ByteBuffer.allocateDirect(size)
    } catch (e: Throwable) {
        System.gc()
        null
    }
    if (buffer == null) return null
    val temp = ByteArray(8192)
    var totalRead = 0
    while (totalRead < size) {
        val toRead = min(size - totalRead, temp.size)
        val read = this.read(temp, 0, toRead)
        if (read == -1) break
        buffer.put(temp, 0, read)
        totalRead += read
    }
    buffer.flip()
    return buffer
}
