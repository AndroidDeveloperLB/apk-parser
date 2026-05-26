package com.lb.apkparserdemo.apk_info

import android.app.ActivityManager
import android.content.Context
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Utility for checking memory availability before performing large allocations during APK parsing.
 * Helps prevent [OutOfMemoryError] by estimating if enough heap or native memory is available.
 */
object MemoryUtils {
    /**
     * Checks if there is enough Java heap memory to allocate the requested size.
     *
     * @param expectedByteCountNeeded Requested size to be allocated in bytes.
     * @return true if there is enough memory to allocate the requested size while maintaining a safety buffer.
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

    /**
     * Checks if there's enough physical RAM for a native allocation (Direct ByteBuffer).
     * Since native memory isn't strictly limited by the Java heap size, we check the device's overall memory state.
     */
    @JvmStatic
    fun isEnoughNativeMemory(context: Context, expectedByteCountNeeded: Long): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // Ensure we are not exceeding the available physical RAM and not pushing the system into low memory.
        val availableRam = memoryInfo.availMem
        if (expectedByteCountNeeded > availableRam) return false

        val remainingAfter = availableRam - expectedByteCountNeeded
        // Ensure we stay at least 50MB above the system's low-memory threshold for overall stability.
        return !memoryInfo.lowMemory && remainingAfter > (memoryInfo.threshold + 50 * 1024 * 1024)
    }

    /**
     * Checks if a ZIP file (or stream) contains entries that will trigger the
     * "only DEFLATED entries can have EXT descriptor" exception on Android API < 26.
     *
     * It scans the Local File Headers for entries where:
     * 1. Method is STORED (0)
     * 2. General Purpose Bit Flag has bit 3 set (0x08)
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
                if (sig[0] != 'P'.toByte() || sig[1] != 'K'.toByte()) break

                if (sig[2] == 3.toByte() && sig[3] == 4.toByte()) { // Local File Header
                    if (bis.read(header, 4, 26) != 26) break
                    val flag = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
                    val method = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)

                    if (method == 0 && (flag and 0x08) != 0) return true

                    // To avoid complex scanning, we just check the first few headers.
                    // Usually if one entry is problematic, others are too.
                } else if (sig[2] == 1.toByte() && sig[3] == 2.toByte()) { // Central Directory
                    break
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Fixes the General Purpose Bit Flag of STORED entries in a ZIP byte array.
     * This allows [java.util.zip.ZipInputStream] on API 24/25 to parse files that
     * would otherwise throw "only DEFLATED entries can have EXT descriptor".
     *
     * It scans for the "PK\x03\x04" signature and if the method is STORED, it unsets bit 3.
     */
    @JvmStatic
    fun patchZipBytesForOldAndroid(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size - 30) {
            if (bytes[i] == 'P'.toByte() && bytes[i + 1] == 'K'.toByte() &&
                    bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) {
                
                val method = (bytes[i + 8].toInt() and 0xFF) or ((bytes[i + 9].toInt() and 0xFF) shl 8)
                if (method == 0) { // STORED
                    // Clear bit 3 (0x08) of General Purpose Bit Flag (offset 6)
                    bytes[i + 6] = (bytes[i + 6].toInt() and 0xF7).toByte()
                }
                // Move forward to avoid redundant checks
                i += 30
            } else {
                i++
            }
        }
    }
}
