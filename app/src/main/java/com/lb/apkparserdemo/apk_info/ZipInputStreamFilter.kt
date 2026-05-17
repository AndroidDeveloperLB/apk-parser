package com.lb.apkparserdemo.apk_info

import com.lb.common_utils.closeSilently
import com.lb.common_utils.readBytesIntoByteArray
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Implementation of [AbstractZipFilter] using [java.util.zip.ZipInputStream].
 * This is NOT seekable and is meant for sequential processing of zip contents.
 * Once an entry is passed, it cannot be revisited unless the stream is reset/recreated.
 *
 * @param zipInputStream The backing [ZipInputStream].
 * @param inclusionPredicate Optional filter to decide which entries to process during iteration.
 */
class ZipInputStreamFilter(
        private val zipInputStream: ZipInputStream,
        private val inclusionPredicate: ((String) -> Boolean)? = null
) : AbstractZipFilter(),
        Closeable {
    private var currentEntry: ZipEntry? = null
    private var currentEntryByteArray: ByteArray? = null
    private var entryNames: MutableList<String>? = null

    private fun shouldInclude(name: String): Boolean {
        if (inclusionPredicate != null) return inclusionPredicate.invoke(name)
        // Default aggressive inclusion list
        if (name == "AndroidManifest.xml" || name == "resources.arsc") return true
        if (name.startsWith("res/")) return true
        if (name.startsWith("META-INF/")) {
            val upper = name.uppercase()
            return upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") ||
                    upper.endsWith("MANIFEST.MF") || upper.endsWith("CERT.SF")
        }
        return false
    }

    override val allEntryNames: List<String>
        get() {
            if (entryNames != null) return entryNames!!
            // This is problematic for InputStream because it consumes it.
            // But usually ZipFileFilter is used for local files.
            return emptyList()
        }

    override fun getByteBufferForEntry(name: String): ByteBuffer? {
        val entry = currentEntry
        if (entry != null && entry.name == name) {
            val bytes = getBytesFromCurrentEntry()
            if (bytes != null) return ByteBuffer.wrap(bytes)
        }
        return getByteArrayForEntries(emptySet(), hashSetOf(name))?.get(name)?.let {
            ByteBuffer.wrap(it)
        }
    }

    override fun getByteArrayForEntries(
            mandatoryEntriesNames: Set<String>,
            extraEntriesNames: Set<String>?
    ): HashMap<String, ByteArray>? {
        try {
            val mandatoryNamesToSearch = mandatoryEntriesNames.toMutableSet()
            val extraNamesToSearch = extraEntriesNames?.toMutableSet() ?: mutableSetOf()
            val totalItemsCount = mandatoryNamesToSearch.size + extraNamesToSearch.size
            val result = HashMap<String, ByteArray>(totalItemsCount)
            while (true) {
                val zipEntry = try {
                    zipInputStream.nextEntry
                } catch (e: Exception) {
                    if (e is java.util.zip.ZipException && e.message?.contains("Unexpected record signature") == true) {
                        // This is common at the end of a ZIP stream if there's padding or a central directory.
                        // We treat it as end-of-stream.
                    }
//                    else {
//                        Log.e("AppLog", "stream filter: ZipInputStreamFilter error calling nextEntry", e)
//                    }
                    null
                }
                if (zipEntry == null) {
//                    if (mandatoryNamesToSearch.isNotEmpty())
//                        Log.e("AppLog", "stream filter: ZipInputStreamFilter end of stream. still missing mandatory: $mandatoryNamesToSearch")
                    close()
                    if (mandatoryNamesToSearch.isEmpty())
                        return result
                    return null
                }
                val entryName = zipEntry.name
                var nameToUse: String? = null
                var isMandatory = false
                if (mandatoryNamesToSearch.contains(entryName)) {
                    nameToUse = entryName
                    isMandatory = true
                } else if (entryName.startsWith("/") && mandatoryNamesToSearch.contains(entryName.substring(1))) {
                    nameToUse = entryName.substring(1)
                    isMandatory = true
                } else if (!entryName.startsWith("/") && mandatoryNamesToSearch.contains("/$entryName")) {
                    nameToUse = "/$entryName"
                    isMandatory = true
                } else if (extraNamesToSearch.contains(entryName)) {
                    nameToUse = entryName
                } else if (entryName.startsWith("/") && extraNamesToSearch.contains(entryName.substring(1))) {
                    nameToUse = entryName.substring(1)
                } else if (!entryName.startsWith("/") && extraNamesToSearch.contains("/$entryName")) {
                    nameToUse = "/$entryName"
                }

                if (nameToUse != null) {
                    val bytes = if (zipEntry.size > 0) {
                        val b = ByteArray(zipEntry.size.toInt())
                        zipInputStream.readBytesIntoByteArray(b)
                        b
                    } else {
                        zipInputStream.readBytes()
                    }
                    if (isMandatory)
                        mandatoryNamesToSearch.remove(nameToUse)
                    else
                        extraNamesToSearch.remove(nameToUse)
                    result[nameToUse] = bytes
                    if (result.size == totalItemsCount) {
                        return result
                    }
                }
            }
        } catch (e: Exception) {
//            Log.e("AppLog", "stream filter: ZipInputStreamFilter error in getByteArrayForEntries", e)
            close()
            return null
        }
    }

    override fun getNextEntryName(): String? {
        try {
            while (true) {
                val zipEntry = zipInputStream.nextEntry
                if (zipEntry == null) {
                    close()
                    return null
                }
                if (!shouldInclude(zipEntry.name)) continue
                currentEntry = zipEntry
                currentEntryByteArray = null
                return zipEntry.name
            }
        } catch (e: Exception) {
            close()
            return null
        }
    }

    override fun getBytesFromCurrentEntry(): ByteArray? {
        currentEntryByteArray?.let { return it }
        try {
            currentEntry.let { zipEntry: ZipEntry? ->
                if (zipEntry == null) {
                    close()
                    return null
                }
                val bytes = if (zipEntry.size > 0) {
                    val b = ByteArray(zipEntry.size.toInt())
                    zipInputStream.readBytesIntoByteArray(b)
                    b
                } else {
                    zipInputStream.readBytes()
                }
                currentEntryByteArray = bytes
                return bytes
            }
        } catch (e: Exception) {
            close()
            return null
        }
    }

    override fun close() {
        currentEntry = null
        currentEntryByteArray = null
        zipInputStream.closeSilently()
    }
}
