package com.lb.apkparserdemo.apk_info

import android.app.ActivityManager
import android.content.Context

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
}
