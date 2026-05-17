package com.lb.apkparserdemo.apk_info

/**
 * A wrapper that prevents the underlying filter from being closed.
 * Useful when sharing a filter between multiple components where some might try to close it.
 */
class NonClosingZipFilter(private val delegate: AbstractZipFilter) : AbstractZipFilter() {
    override fun getNextEntryName(): String? = delegate.getNextEntryName()
    override fun getBytesFromCurrentEntry(): ByteArray? = delegate.getBytesFromCurrentEntry()
    override val allEntryNames: List<String> get() = delegate.allEntryNames
    override fun containsEntry(name: String): Boolean? = delegate.containsEntry(name)
    override fun getByteArrayForEntries(mandatoryEntriesNames: Set<String>, extraEntriesNames: Set<String>?): HashMap<String, ByteArray>? =
            delegate.getByteArrayForEntries(mandatoryEntriesNames, extraEntriesNames)

    override fun close() {
        // Do nothing
    }
}
