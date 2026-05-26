package com.lb.apkparserdemo.apk_info

import android.content.Context
import com.lb.apkparserdemo.apk_info.zip.BoundedSeekableByteChannel
import com.lb.common_utils.readBytesIntoByteArray
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/**
 * Holds metadata for a ZIP entry gathered from the Central Directory.
 */
data class ZipEntryInfo(
    val name: String,
    val method: Int,
    val flag: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long
)

/**
 * Utility to parse ZIP files using the Central Directory.
 * This is robust against the "EXT descriptor" issue on API 24/25 because it ignores Local File Headers.
 */
object ZipIndexer {

    fun createIndex(channel: SeekableByteChannel): Map<String, ZipEntryInfo> {
        val index = mutableMapOf<String, ZipEntryInfo>()
        try {
            val size = channel.size()
            if (size < 22) return index
            
            // 1. Find EOCD (End of Central Directory)
            val scanSize = if (size > 65536 + 22) 65536 + 22 else size
            channel.position(size - scanSize)
            val scanBuffer = ByteBuffer.allocate(scanSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(scanBuffer)
            val bytes = scanBuffer.array()
            var eocdPos = -1L
            for (i in bytes.size - 22 downTo 0) {
                if (bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte() && 
                    bytes[i+2] == 0x05.toByte() && bytes[i+3] == 0x06.toByte()) {
                    eocdPos = size - scanSize + i
                    break
                }
            }
            if (eocdPos == -1L) return index
            
            // 2. Read EOCD info
            channel.position(eocdPos + 10)
            val eocdData = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(eocdData)
            eocdData.flip()
            val totalEntries = eocdData.short.toInt() and 0xFFFF
            val cdSize = eocdData.int.toLong() and 0xFFFFFFFFL
            val cdOffset = eocdData.int.toLong() and 0xFFFFFFFFL
            
            // 3. Read Central Directory
            channel.position(cdOffset)
            val cdBuffer = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(cdBuffer)
            cdBuffer.flip()
            
            for (i in 0 until totalEntries) {
                if (cdBuffer.remaining() < 46) break
                val sig = cdBuffer.int
                if (sig != 0x02014b50) break
                
                cdBuffer.position(cdBuffer.position() + 4) // skip versions
                val flag = cdBuffer.short.toInt() and 0xFFFF
                val method = cdBuffer.short.toInt() and 0xFFFF
                cdBuffer.position(cdBuffer.position() + 4) // skip time/date
                val crc = cdBuffer.int.toLong() and 0xFFFFFFFFL
                val compSize = cdBuffer.int.toLong() and 0xFFFFFFFFL
                val uncompSize = cdBuffer.int.toLong() and 0xFFFFFFFFL
                val nameLen = cdBuffer.short.toInt() and 0xFFFF
                val extraLen = cdBuffer.short.toInt() and 0xFFFF
                val commentLen = cdBuffer.short.toInt() and 0xFFFF
                cdBuffer.position(cdBuffer.position() + 8) // skip disk/attr
                val localOffset = cdBuffer.int.toLong() and 0xFFFFFFFFL
                
                val nameBytes = ByteArray(nameLen)
                cdBuffer.get(nameBytes)
                val name = String(nameBytes)
                
                index[name] = ZipEntryInfo(name, method, flag, crc, compSize, uncompSize, localOffset)
                cdBuffer.position(cdBuffer.position() + extraLen + commentLen)
            }
        } catch (_: Exception) {}
        return index
    }
}

/**
 * Implementation of [AbstractZipFilter] that uses an index from the Central Directory.
 * This is highly robust and provides random access even on API 24/25.
 */
class SeekableZipFilter(
    private val context: Context,
    private val channel: SeekableByteChannel,
    private val index: Map<String, ZipEntryInfo>,
    private val inclusionPredicate: ((String) -> Boolean)? = null
) : AbstractZipFilter(), Closeable {
    override val isSeekable: Boolean get() = true
    private var entryList = index.values.toList()
    private var currentIndex = 0

    override val allEntryNames: List<String> get() = entryList.map { it.name }

    override fun getNextEntryName(): String? {
        while (currentIndex < entryList.size) {
            val entry = entryList[currentIndex++]
            if (shouldInclude(entry.name)) return entry.name
        }
        return null
    }

    private fun shouldInclude(name: String): Boolean {
        if (inclusionPredicate != null) return inclusionPredicate.invoke(name)
        if (name == "AndroidManifest.xml" || name == "resources.arsc") return true
        if (name.startsWith("res/")) return true
        return name.startsWith("META-INF/")
    }

    override fun getBytesFromCurrentEntry(): ByteArray? {
        if (currentIndex <= 0 || currentIndex > entryList.size) return null
        return getBytesForEntry(entryList[currentIndex - 1])
    }

    override fun getByteArrayForEntries(mandatoryEntriesNames: Set<String>, extraEntriesNames: Set<String>?): HashMap<String, ByteArray>? {
        val result = HashMap<String, ByteArray>()
        for (name in mandatoryEntriesNames) {
            val bytes = getBytesForEntry(name) ?: return null
            result[name] = bytes
        }
        extraEntriesNames?.forEach { name ->
            getBytesForEntry(name)?.let { result[name] = it }
        }
        return result
    }

    private fun getBytesForEntry(name: String): ByteArray? = index[name]?.let { getBytesForEntry(it) }

    private fun getBytesForEntry(info: ZipEntryInfo): ByteArray? {
        return try {
            // Find start of data by skipping Local File Header
            channel.position(info.localHeaderOffset + 26)
            val lenBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(lenBuffer)
            lenBuffer.flip()
            val nameLen = lenBuffer.short.toInt() and 0xFFFF
            val extraLen = lenBuffer.short.toInt() and 0xFFFF
            
            channel.position(info.localHeaderOffset + 30 + nameLen + extraLen)
            
            if (info.method == 0) { // STORED
                val bytes = ByteArray(info.uncompressedSize.toInt())
                channel.read(ByteBuffer.wrap(bytes))
                bytes
            } else { // DEFLATED
                val compressedBytes = ByteArray(info.compressedSize.toInt())
                channel.read(ByteBuffer.wrap(compressedBytes))
                val inflater = java.util.zip.Inflater(true)
                inflater.setInput(compressedBytes)
                val result = ByteArray(info.uncompressedSize.toInt())
                inflater.inflate(result)
                inflater.end()
                result
            }
        } catch (_: Exception) { null }
    }

    override fun close() {
        // We don't close the channel as it's usually owned by the caller
    }
}
