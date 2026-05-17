package com.lb.apkparserdemo.apk_info

import android.content.Context
import com.lb.apkparserdemo.apk_info.zip.MappableSeekableByteChannel
import com.lb.common_utils.closeSilently
import com.lb.common_utils.readBytesIntoByteArray
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Enumeration

/**
 * Implementation of [AbstractZipFilter] using Apache Commons Compress [org.apache.commons.compress.archivers.zip.ZipFile].
 * This is [isSeekable] and supports memory mapping for uncompressed (STORED) entries if the underlying channel allows it.
 *
 * Note: Typically performs slower than the built-in [ZipFileFilter]. Use only if the standard one has issues.
 *
 * @param context Android context.
 * @param zipFile The backing Apache ZipFile.
 * @param inclusionPredicate Optional filter to decide which entries to process during iteration.
 * @param underlyingChannel The channel used by the zip file, used for potential memory mapping.
 */
class ApacheZipFileFilter(
        private val context: Context,
        private val zipFile: org.apache.commons.compress.archivers.zip.ZipFile,
        private val inclusionPredicate: ((String) -> Boolean)? = null,
        private val underlyingChannel: java.nio.channels.SeekableByteChannel? = null
) :
        AbstractZipFilter(), Closeable {
    override val isSeekable: Boolean get() = true
    private var entries: Enumeration<out ZipArchiveEntry>? = null

    @Suppress("MemberVisibilityCanBePrivate")
    var currentEntry: ZipArchiveEntry? = null

    private fun shouldInclude(name: String): Boolean {
        if (inclusionPredicate != null) return inclusionPredicate.invoke(name)
        if (name == "AndroidManifest.xml" || name == "resources.arsc") return true
        if (name.startsWith("res/")) return true
        if (name.startsWith("META-INF/")) {
            val upper = name.uppercase()
            return upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") ||
                    upper.endsWith("MANIFEST.MF") || upper.endsWith("CERT.SF")
        }
        return false
    }

    init {
        try {
            this.entries = zipFile.entries
        } catch (e: Exception) {
            close()
        }
    }

    override val allEntryNames: List<String>
        get() = zipFile.entries.asSequence().map { it.name }.toList()

    override fun containsEntry(name: String): Boolean {
        var entry = zipFile.getEntry(name)
        if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
        return entry != null
    }

    override fun getByteBufferForEntry(name: String): ByteBuffer? {
        var entry = zipFile.getEntry(name)
        if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
        if (entry == null) {
//            android.util.Log.d("AppLog", "icon fetching: ApacheZipFileFilter entry not found: $name")
            return null
        }
        if (entry.method == java.util.zip.ZipEntry.STORED) {
            if (underlyingChannel is FileChannel) {
                try {
                    return underlyingChannel.map(FileChannel.MapMode.READ_ONLY, entry.dataOffset, entry.size)
                } catch (e: Exception) {
                }
            } else
                if (underlyingChannel is MappableSeekableByteChannel) {
                try {
                    return underlyingChannel.map(entry.dataOffset, entry.size)
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError)
                        System.gc()
                }
            }
        }
        return try {
            zipFile.getInputStream(entry).use { it.readIntoDirectBuffer(context, entry.size.toInt()) }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError)
                System.gc()
            super.getByteBufferForEntry(name)
        }
    }

    override fun getByteArrayForEntries(
            mandatoryEntriesNames: Set<String>,
            extraEntriesNames: Set<String>?
    ): HashMap<String, ByteArray>? {
        try {
            val totalItemsCount = mandatoryEntriesNames.size + (extraEntriesNames?.size ?: 0)
            val result = HashMap<String, ByteArray>(totalItemsCount)
            for (name in mandatoryEntriesNames) {
                var entry: ZipArchiveEntry? = zipFile.getEntry(name)
                if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
                if (entry == null) {
//                    android.util.Log.d("AppLog", "icon fetching: ApacheZipFileFilter mandatory entry not found: $name")
                    return null
                }
                val bytes = ByteArray(entry.size.toInt())
                zipFile.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                result[name] = bytes
            }
            if (extraEntriesNames != null)
                for (name in extraEntriesNames) {
                    var entry: ZipArchiveEntry? = zipFile.getEntry(name)
                    if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
                    if (entry == null) {
//                        android.util.Log.d("AppLog", "icon fetching: ApacheZipFileFilter extra entry not found: $name")
                        continue
                    }
                    val bytes = ByteArray(entry.size.toInt())
                    zipFile.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                    result[name] = bytes
                }
            return result
        } catch (e: Throwable) {
            return null
        }
    }


    override fun getNextEntryName(): String? {
        val enu = entries ?: return null
        try {
            while (enu.hasMoreElements()) {
                val zipEntry = enu.nextElement()
                if (!shouldInclude(zipEntry.name)) continue
                currentEntry = zipEntry
                return zipEntry.name
            }
            currentEntry = null
            entries = null
            return null
        } catch (e: Exception) {
            currentEntry = null
            entries = null
            return null
        }
    }

    override fun getBytesFromCurrentEntry(): ByteArray? {
        currentEntry.let { zipEntry: ZipArchiveEntry? ->
            if (zipEntry == null)
                return null
            return try {
                zipFile.getInputStream(zipEntry).readBytes()
            } catch (e: Exception) {
                close()
                null
            }
        }
    }

    override fun close() {
        entries = null
        currentEntry = null
        zipFile.closeSilently()
    }

}
