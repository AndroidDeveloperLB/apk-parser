package com.lb.apkparserdemo.apk_info

/**
 * A composite [AbstractZipFilter] that delegates to a list of other filters.
 * It is primarily used to handle multiple APK files (e.g., base and splits) as if they were a single unit.
 *
 * Note: It only supports [getByteArrayForEntries] and [allEntryNames]. Sequential iteration is not supported.
 *
 * @param filters The list of [AbstractZipFilter]s to aggregate.
 */
class MultiZipFilter(private val filters: List<AbstractZipFilter>) : AbstractZipFilter() {
    override fun getNextEntryName(): String? = throw UnsupportedOperationException("MultiZipFilter only supports getByteArrayForEntries")
    override fun getBytesFromCurrentEntry(): ByteArray? = throw UnsupportedOperationException("MultiZipFilter only supports getByteArrayForEntries")

    override val allEntryNames: List<String>
        get() = filters.flatMap { it.allEntryNames }.distinct()

    override fun getByteArrayForEntries(mandatoryEntriesNames: Set<String>, extraEntriesNames: Set<String>?): HashMap<String, ByteArray>? {
        val result = HashMap<String, ByteArray>()
        val missingMandatory = mandatoryEntriesNames.toMutableSet()
        val requestedExtra = extraEntriesNames?.toMutableSet() ?: mutableSetOf()

//        android.util.Log.d("AppLog", "icon fetching: MultiZipFilter searching for mandatory: $missingMandatory, extra: $requestedExtra in ${filters.size} filters")

        for (filter in filters) {
            val filterResult = filter.getByteArrayForEntries(emptySet(), missingMandatory + requestedExtra)
            if (filterResult != null) {
                for ((name, bytes) in filterResult) {
                    if (!result.containsKey(name)) {
//                        android.util.Log.d("AppLog", "icon fetching: MultiZipFilter found entry: $name")
                        result[name] = bytes
                        missingMandatory.remove(name)
                        requestedExtra.remove(name)
                    }
                }
            }
            if (missingMandatory.isEmpty() && requestedExtra.isEmpty()) {
//                android.util.Log.d("AppLog", "icon fetching: MultiZipFilter found all entries early")
                break
            }
        }

//        if (missingMandatory.isNotEmpty()) {
//            android.util.Log.d("AppLog", "icon fetching: MultiZipFilter failed to find mandatory entries: $missingMandatory")
//        }
        return if (missingMandatory.isEmpty()) result else null
    }

    override fun close() {
        filters.forEach { it.close() }
    }
}
