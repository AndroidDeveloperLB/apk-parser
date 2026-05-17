package com.lb.apkparserdemo.apk_info

import android.content.Context
import com.lb.common_utils.closeSilently
import com.lb.common_utils.readBytesIntoByteArray
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Implementation of [AbstractZipFilter] using the standard [java.util.zip.ZipFile].
 * This implementation is [isSeekable] and efficient for random access.
 *
 * @param context Android context for resource management.
 * @param zipFile The backing [ZipFile] instance.
 * @param inclusionPredicate Optional filter to decide which entries to process during iteration.
 */
class ZipFileFilter(
        private val context: Context,
        private val zipFile: ZipFile,
        private val inclusionPredicate: ((String) -> Boolean)? = null
) : AbstractZipFilter(), Closeable {
    override val isSeekable: Boolean get() = true
    private var entries: Enumeration<out ZipEntry>? = null

    @Suppress("MemberVisibilityCanBePrivate")
    var currentEntry: ZipEntry? = null

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
            this.entries = zipFile.entries()
        } catch (e: Exception) {
            close()
        }
    }

    override val allEntryNames: List<String>
        get() = zipFile.entries().asSequence().map { it.name }.toList()

    override fun containsEntry(name: String): Boolean {
        var entry = zipFile.getEntry(name)
        if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
        return entry != null
    }

    override fun getByteBufferForEntry(name: String): ByteBuffer? {
        var entry = zipFile.getEntry(name)
        if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
        if (entry == null) return null
        return try {
            zipFile.getInputStream(entry).use { it.readIntoDirectBuffer(context, entry.size.toInt()) }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) System.gc()
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
                var entry: ZipEntry? = zipFile.getEntry(name)
                if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
                if (entry == null) {
                    // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) MISSED mandatory entry: $name")
                    return null
                }
                val bytes = ByteArray(entry.size.toInt())
                zipFile.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                result[name] = bytes
                // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) found mandatory entry: $name")
            }
            if (extraEntriesNames != null)
                for (name in extraEntriesNames) {
                    var entry: ZipEntry? = zipFile.getEntry(name)
                    if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
                    if (entry == null) continue
                    val bytes = ByteArray(entry.size.toInt())
                    zipFile.getInputStream(entry).use { it.readBytesIntoByteArray(bytes) }
                    result[name] = bytes
                    // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) found extra entry: $name")
                }
            return result
        } catch (e: Throwable) {
            // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) exception: ${e.message}")
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
        currentEntry.let { zipEntry: ZipEntry? ->
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
