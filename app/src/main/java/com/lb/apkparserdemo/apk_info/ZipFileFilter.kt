package com.lb.apkparserdemo.apk_info

import java.io.Closeable
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ZipFileFilter(private val zipFile: ZipFile) : AbstractZipFilter(), Closeable {
    private var entries: Enumeration<out ZipEntry>? = null

    @Suppress("MemberVisibilityCanBePrivate")
    var currentEntry: ZipEntry? = null

    init {
        try {
            this.entries = zipFile.entries()
        } catch (e: Exception) {
            close()
        }
    }

    override val allEntryNames: List<String>
        get() = zipFile.entries().asSequence().map { it.name }.toList()

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
                result[name] = zipFile.getInputStream(entry).readBytes()
                // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) found mandatory entry: $name")
            }
            if (extraEntriesNames != null)
                for (name in extraEntriesNames) {
                    var entry: ZipEntry? = zipFile.getEntry(name)
                    if (entry == null && name.startsWith("/")) entry = zipFile.getEntry(name.substring(1))
                    if (entry == null) continue
                    result[name] = zipFile.getInputStream(entry).readBytes()
                    // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) found extra entry: $name")
                }
            return result
        } catch (e: Exception) {
            // android.util.Log.d("AppLog", "icon fetching: ZipFileFilter (${zipFile.name}) exception: ${e.message}")
            return null
        }
    }


    override fun getNextEntryName(): String? {
        entries.let {
            if (it == null)
                return null
            try {
                it.nextElement().let { zipEntry: ZipEntry? ->
                    if (zipEntry == null) {
//                        close()
                        currentEntry = null
                        entries = null
                        return null
                    }
                    currentEntry = zipEntry
                    return zipEntry.name
                }
            } catch (e: Exception) {
                currentEntry = null
                entries = null
//                close()
                return null
            }
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
        try {
            zipFile.close()
        } catch (e: Exception) {
        }
    }

}
