package com.lb.apkparserdemo.apk_info

import android.app.ActivityManager
import android.content.Context
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Utility for checking memory availability before performing large allocations during APK parsing.
 * Helps prevent [OutOfMemoryError] by estimating if enough heap or native memory is available.
 */
object MemoryUtils {
    /**
     * Checks if there is enough Java heap memory to allocate the requested size.
     */
    @JvmStatic
    fun isEnoughMemoryForApkParsing(expectedByteCountNeeded: Long): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory
        if (expectedByteCountNeeded > availableMemory) return false
        val remainingMemoryAfterAllocation = availableMemory - expectedByteCountNeeded
        return remainingMemoryAfterAllocation >= 20 * 1024 * 1024 && usedMemory + expectedByteCountNeeded <= maxMemory * 0.9
    }

    @JvmStatic
    fun isEnoughNativeMemory(context: Context, expectedByteCountNeeded: Long): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availableRam = memoryInfo.availMem
        if (expectedByteCountNeeded > availableRam) return false
        val remainingAfter = availableRam - expectedByteCountNeeded
        return !memoryInfo.lowMemory && remainingAfter > (memoryInfo.threshold + 50 * 1024 * 1024)
    }

    /**
     * Checks if a ZIP contains entries that trigger the "only DEFLATED entries can have EXT descriptor"
     * exception on Android API < 26.
     */
    @JvmStatic
    fun hasProblematicZipEntries(inputStream: InputStream): Boolean {
        val bis = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        val header = ByteArray(30)
        try {
            while (true) {
                bis.mark(4)
                val sig = ByteArray(4)
                if (bis.read(sig) != 4) break
                if (sig[0] == 'P'.toByte() && sig[1] == 'K'.toByte() && sig[2] == 3.toByte() && sig[3] == 4.toByte()) {
                    if (bis.read(header, 4, 26) != 26) break
                    val flag = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
                    val method = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
                    if (method == 0 && (flag and 0x08) != 0) return true
                } else if (sig[0] == 'P'.toByte() && sig[1] == 'K'.toByte() && sig[2] == 1.toByte() && sig[3] == 2.toByte()) {
                    break // Central Directory reached
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * A wrapper [InputStream] that unsets the "Data Descriptor" flag (bit 3) for STORED entries
     * on-the-fly. This prevents [java.util.zip.ZipInputStream] from throwing an exception on API 24/25.
     */
    class ZipPatchInputStream(inputStream: InputStream) : FilterInputStream(if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val bytesRead = super.read(b, off, len)
            if (bytesRead >= 30) {
                var i = 0
                while (i <= bytesRead - 30) {
                    if (b[off + i] == 'P'.toByte() && b[off + i + 1] == 'K'.toByte() &&
                            b[off + i + 2] == 3.toByte() && b[off + i + 3] == 4.toByte()) {
                        val flagPos = off + i + 6
                        val methodPos = off + i + 8
                        val method = (b[methodPos].toInt() and 0xFF) or ((b[methodPos + 1].toInt() and 0xFF) shl 8)
                        if (method == 0) { // STORED
                            // Clear bit 3 (0x08) of flag
                            b[flagPos] = (b[flagPos].toInt() and 0xF7).toByte()
                        }
                        i += 30
                    } else i++
                }
            }
            return bytesRead
        }
    }

    @JvmStatic
    fun patchZipBytesForOldAndroid(bytes: ByteArray) {
        var i = 0
        while (i <= bytes.size - 30) {
            if (bytes[i] == 'P'.toByte() && bytes[i + 1] == 'K'.toByte() &&
                    bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) {
                val method = (bytes[i + 8].toInt() and 0xFF) or ((bytes[i + 9].toInt() and 0xFF) shl 8)
                if (method == 0) {
                    bytes[i + 6] = (bytes[i + 6].toInt() and 0xF7).toByte()
                }
                i += 30
            } else i++
        }
    }
}
