package com.lb.apkparserdemo.apk_info

import java.io.Closeable

abstract class AbstractZipFilter : Closeable {
    abstract fun getNextEntryName(): String?
    abstract fun getBytesFromCurrentEntry(): ByteArray?

    /**Note: depending on the implementation, this might be usable only once, and before any other function (because once you call it, there is no turning back)
     * if there is an error of any kind, null might be returned*/
    open fun getByteArrayForEntries(
        mandatoryEntriesNames: Set<String>,
        extraEntriesNames: Set<String>? = null
    ): HashMap<String, ByteArray>? {
        try {
            var remainingMandatoryNames = mandatoryEntriesNames.size
            val totalItemsCount = remainingMandatoryNames + (extraEntriesNames?.size ?: 0)
            val result = HashMap<String, ByteArray>(totalItemsCount)
            while (true) {
                val entryName = getNextEntryName()
                if (entryName == null) {
                    if (remainingMandatoryNames == 0)
                        return result
                    return null
                }
                val foundMandatoryEntry = mandatoryEntriesNames.contains(entryName)
                if (foundMandatoryEntry || extraEntriesNames?.contains(entryName) == true) {
                    val bytesFromCurrentEntry = getBytesFromCurrentEntry()
                    if (bytesFromCurrentEntry != null) {
                        if (foundMandatoryEntry)
                            --remainingMandatoryNames
                        result[entryName] = bytesFromCurrentEntry
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

class MultiZipFilter(private val filters: List<AbstractZipFilter>) : AbstractZipFilter() {
    override fun getNextEntryName(): String? = throw UnsupportedOperationException("MultiZipFilter only supports getByteArrayForEntries")
    override fun getBytesFromCurrentEntry(): ByteArray? = throw UnsupportedOperationException("MultiZipFilter only supports getByteArrayForEntries")

    override fun getByteArrayForEntries(mandatoryEntriesNames: Set<String>, extraEntriesNames: Set<String>?): HashMap<String, ByteArray>? {
        val result = HashMap<String, ByteArray>()
        val missingMandatory = mandatoryEntriesNames.toMutableSet()
        val requestedExtra = extraEntriesNames?.toMutableSet() ?: mutableSetOf()

        for (filter in filters) {
            val filterResult = filter.getByteArrayForEntries(emptySet(), missingMandatory + requestedExtra)
            if (filterResult != null) {
                for ((name, bytes) in filterResult) {
                    if (!result.containsKey(name)) {
                        result[name] = bytes
                        missingMandatory.remove(name)
                        requestedExtra.remove(name)
                    }
                }
            }
            if (missingMandatory.isEmpty() && requestedExtra.isEmpty()) break
        }

        return if (missingMandatory.isEmpty()) result else null
    }

    override fun close() {
        filters.forEach { it.close() }
    }
}
