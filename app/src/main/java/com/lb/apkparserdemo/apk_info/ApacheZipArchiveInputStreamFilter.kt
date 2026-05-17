package com.lb.apkparserdemo.apk_info

import com.lb.common_utils.closeSilently
import com.lb.common_utils.readBytesIntoByteArray
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.Closeable

/**
 * Implementation of [AbstractZipFilter] using Apache Commons Compress [ZipArchiveInputStream].
 * Generally faster than the built-in [java.util.zip.ZipInputStream].
 * Like other input stream filters, this is NOT seekable.
 *
 * @param zipArchiveInputStream The backing [ZipArchiveInputStream].
 * @param inclusionPredicate Optional filter to decide which entries to process during iteration.
 */
class ApacheZipArchiveInputStreamFilter(
        private val zipArchiveInputStream: ZipArchiveInputStream,
        private val inclusionPredicate: ((String) -> Boolean)? = null
) :
        AbstractZipFilter(), Closeable {
    private var currentEntry: ArchiveEntry? = null
    private var currentEntryByteArray: ByteArray? = null

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
                    zipArchiveInputStream.nextEntry
                } catch (e: Throwable) {
                    if (e is java.util.zip.ZipException && e.message?.contains("Unexpected record signature") == true) {
                        // This is common at the end of a ZIP stream if there's padding or a central directory.
                        // We treat it as end-of-stream.
                    }
//                    else {
//                        Log.e("AppLog", "stream filter: ApacheZipArchiveInputStreamFilter error calling nextEntry", e)
//                    }
                    null
                }
                if (zipEntry == null) {
//                    if (mandatoryNamesToSearch.isNotEmpty())
//                        Log.e("AppLog", "stream filter: ApacheZipArchiveInputStreamFilter end of stream. still missing mandatory: $mandatoryNamesToSearch")
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
                        zipArchiveInputStream.readBytesIntoByteArray(b)
                        b
                    } else {
                        zipArchiveInputStream.readBytes()
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
        } catch (e: Throwable) {
//            Log.e("AppLog", "stream filter: ApacheZipArchiveInputStreamFilter error in getByteArrayForEntries", e)
            close()
            return null
        }
    }

    override fun getNextEntryName(): String? {
        try {
            while (true) {
                val zipEntry = zipArchiveInputStream.nextEntry
                if (zipEntry == null) {
                    close()
                    return null
                }
                if (!shouldInclude(zipEntry.name)) continue
                currentEntry = zipEntry
                currentEntryByteArray = null
                return zipEntry.name
            }
        } catch (e: Throwable) {
            close()
            return null
        }
    }

    override fun getBytesFromCurrentEntry(): ByteArray? {
        currentEntryByteArray?.let { return it }
        try {
            currentEntry.let { zipEntry: ArchiveEntry? ->
                if (zipEntry == null) {
                    close()
                    return null
                }
                val bytes = if (zipEntry.size > 0) {
                    val b = ByteArray(zipEntry.size.toInt())
                    zipArchiveInputStream.readBytesIntoByteArray(b)
                    b
                } else {
                    zipArchiveInputStream.readBytes()
                }
                currentEntryByteArray = bytes
                return bytes
            }
        } catch (e: Throwable) {
            close()
            return null
        }
    }

    override fun close() {
        currentEntry = null
        currentEntryByteArray = null
        zipArchiveInputStream.closeSilently()
    }
}
