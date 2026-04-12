package com.lb.apkparserdemo.apk_info

import java.io.Closeable
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ZipInputStreamFilter(private val zipInputStream: ZipInputStream) : AbstractZipFilter(),
    Closeable {
    private var currentEntry: ZipEntry? = null
    private var currentEntryByteArray: ByteArray? = null
    private var entryNames: MutableList<String>? = null

    override val allEntryNames: List<String>
        get() {
            if (entryNames != null) return entryNames!!
            // This is problematic for InputStream because it consumes it.
            // But usually ZipFileFilter is used for local files.
            return emptyList()
        }

    override fun getNextEntryName(): String? {
        try {
            zipInputStream.nextEntry.let { zipEntry: ZipEntry? ->
                if (zipEntry == null) {
                    close()
                    return null
                }
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
                zipInputStream.readBytes().let {
                    currentEntryByteArray = it
                    return it
                }
            }
        } catch (e: Exception) {
            close()
            return null
        }
    }

    override fun close() {
        currentEntry = null
        currentEntryByteArray = null
        try {
            zipInputStream.close()
        } catch (e: Exception) {
        }
    }
}
