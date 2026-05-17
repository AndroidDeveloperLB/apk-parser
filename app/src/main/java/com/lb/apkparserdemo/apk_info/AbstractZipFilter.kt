package com.lb.apkparserdemo.apk_info

import java.io.Closeable

/**
 * An abstraction layer over different ways to access zip/APK contents.
 * It allows the parser to work with [java.util.zip.ZipFile], [java.util.zip.ZipInputStream],
 * or other implementations (like Apache Commons Compress) interchangeably.
 */
abstract class AbstractZipFilter : Closeable {
    /** Whether the implementation supports efficient random access (seeking) to entries. */
    open val isSeekable: Boolean get() = false

    /**
     * Returns the name of the next entry in the zip, or null if there are no more.
     * Note: This might advance the stream in non-seekable implementations.
     */
    abstract fun getNextEntryName(): String?

    /** Returns the raw bytes of the current entry. */
    abstract fun getBytesFromCurrentEntry(): ByteArray?

    /**
     * Efficiently retrieves a specific entry as a [java.nio.ByteBuffer].
     * Implementations might optimize this if they are [isSeekable].
     */
    open fun getByteBufferForEntry(name: String): java.nio.ByteBuffer? =
            getByteArrayForEntries(hashSetOf(name))?.get(name)?.let { java.nio.ByteBuffer.wrap(it) }

    /** Checks if the zip contains an entry with the given name. */
    open fun containsEntry(name: String): Boolean? = if (allEntryNames.isNotEmpty()) allEntryNames.contains(name) else null

    /** Returns a list of all entry names in the zip, if available. */
    open val allEntryNames: List<String>
        get() = emptyList()

    /**
     * Retrieves multiple entries by name.
     *
     * Note: depending on the implementation, this might be usable only once, and before any other function
     * (because once you call it, there is no turning back in non-seekable streams).
     *
     * @param mandatoryEntriesNames Entries that MUST be found for success.
     * @param extraEntriesNames Optional entries to also search for.
     * @return A map of name to bytes, or null if any mandatory entry was missing.
     */
    open fun getByteArrayForEntries(
            mandatoryEntriesNames: Set<String>,
            extraEntriesNames: Set<String>? = null
    ): HashMap<String, ByteArray>? {
        try {
            val mandatoryNamesToSearch = mandatoryEntriesNames.toMutableSet()
            val extraNamesToSearch = extraEntriesNames?.toMutableSet() ?: mutableSetOf()
            val totalItemsCount = mandatoryNamesToSearch.size + extraNamesToSearch.size
            val result = HashMap<String, ByteArray>(totalItemsCount)
            while (true) {
                val entryName = getNextEntryName()
                if (entryName == null) {
                    if (mandatoryNamesToSearch.isEmpty())
                        return result
                    return null
                }
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
                    val bytesFromCurrentEntry = getBytesFromCurrentEntry()
                    if (bytesFromCurrentEntry != null) {
                        if (isMandatory)
                            mandatoryNamesToSearch.remove(nameToUse)
                        else
                            extraNamesToSearch.remove(nameToUse)
                        result[nameToUse] = bytesFromCurrentEntry
                        if (result.size == totalItemsCount)
                            return result
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

}
