package com.lb.apkparserdemo.apk_info

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.WorkerThread
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Light-weight parser for extracting basic information from an APK's AndroidManifest.xml
 * without needing to parse the entire APK or its resource table.
 */
object ApkManifestParser {
    private const val CHUNK_AXML_FILE = 0x00080003
    private const val CHUNK_STRING_POOL = 0x001C0001
    private const val CHUNK_START_TAG = 0x00100102
    private const val UTF8_FLAG = 0x00000100

    /**
     * Data class representing the core info extracted from an APK manifest.
     *
     * @property packageName The package name of the app.
     * @property versionCode The version code of the app.
     * @property splitName The name of the split, if this is a split APK.
     * @property isSplit True if this is a split APK.
     * @property minSdkVersion The minimum SDK version, if requested and available.
     */
    data class SimpleApkInfo(
            val packageName: String?,
            val versionCode: Long?,
            val splitName: String?,
            val isSplit: Boolean,
            val minSdkVersion: Int? = null
    )

    /**
     * Parses the manifest from an [InputStream].
     *
     * @param manifestEntryInputStream The stream positioned at the start of AndroidManifest.xml
     * @param entrySize The size of the entry if known, else -1
     * @param requestFetchingMinSdkVersion If true, keeps parsing until minSdkVersion is found.
     * @return [SimpleApkInfo] if successful, null otherwise.
     */
    @WorkerThread
    fun parseManifestInputStream(manifestEntryInputStream: InputStream, entrySize: Long = -1L,
                                 requestFetchingMinSdkVersion: Boolean = false): SimpleApkInfo? {
        val bytes = if (entrySize > 0) {
            if (entrySize > Integer.MAX_VALUE)
                return null
            readExactBytes(manifestEntryInputStream, entrySize.toInt())
        } else {
            readManifestBytes(manifestEntryInputStream)
        } ?: return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 8) return null
        if (buffer.int != CHUNK_AXML_FILE) return null
        buffer.int // Skip file size
        var stringPoolOffset = -1
        var pkg: String? = null
        var vCode: Long? = null
        var split: String? = null
        var minSdk: Int? = null
        var foundManifest = false
        var foundUsesSdk = false

        while (buffer.hasRemaining()) {
            val chunkPos = buffer.position()
            val chunkType = try {
                buffer.int
            } catch (e: Exception) {
                break
            }
            val chunkSize = try {
                buffer.int
            } catch (e: Exception) {
                break
            }
            if (chunkSize <= 0) break

            when (chunkType) {
                CHUNK_STRING_POOL -> stringPoolOffset = chunkPos
                CHUNK_START_TAG -> {
                    try {
                        buffer.int // line
                        buffer.int // comment
                        buffer.int // ns
                        val nameIndex = buffer.int
                        val tagName = getString(buffer, stringPoolOffset, nameIndex)

                        if (tagName == "manifest") {
                            buffer.short // attrStart
                            buffer.short // attrSize
                            val attrCount = buffer.short.toInt() and 0xFFFF
                            buffer.position(buffer.position() + 6) // Skip classIndex + padding
                            for (i in 0 until attrCount) {
                                buffer.int // ns
                                val attrNameIndex = buffer.int
                                buffer.int // rawValue
                                buffer.int // typedValueType
                                val data = buffer.int
                                val attrName = getString(buffer, stringPoolOffset, attrNameIndex)
                                when (attrName) {
                                    "package" -> pkg = getString(buffer, stringPoolOffset, data)
                                    "versionCode" -> vCode = data.toLong()
                                    "split" -> split = getString(buffer, stringPoolOffset, data)
                                }
                            }
                            foundManifest = true
                        } else if (requestFetchingMinSdkVersion && tagName == "uses-sdk") {
                            buffer.short // attrStart
                            buffer.short // attrSize
                            val attrCount = buffer.short.toInt() and 0xFFFF
                            buffer.position(buffer.position() + 6) // Skip classIndex + padding
                            for (i in 0 until attrCount) {
                                buffer.int // ns
                                val attrNameIndex = buffer.int
                                buffer.int // rawValue
                                val typedValueType = buffer.int
                                val data = buffer.int
                                val attrName = getString(buffer, stringPoolOffset, attrNameIndex)
                                if (attrName == "minSdkVersion") {
                                    val type = typedValueType ushr 24
                                    minSdk = if (type == 3) {
                                        getString(buffer, stringPoolOffset, data)?.toIntOrNull()
                                    } else {
                                        data
                                    }
                                }
                            }
                            foundUsesSdk = true
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors for this specific tag
                    }
                    // Break early if we got what we needed
                    if (foundManifest && (!requestFetchingMinSdkVersion || foundUsesSdk)) {
                        break
                    }
                }
            }
            val nextPos = chunkPos + chunkSize
            if (nextPos > buffer.limit() || nextPos < 0) break
            buffer.position(nextPos)
        }

        return if (foundManifest) {
            SimpleApkInfo(pkg, vCode, split, !split.isNullOrEmpty(), minSdk)
        } else {
            null
        }
    }

    fun findAndParseManifest(apkFileInputStream: InputStream, requestFetchingMinSdkVersion: Boolean = false): SimpleApkInfo? {
        // Switching to Apache ZipArchiveInputStream for speed
        val zipIn = ZipArchiveInputStream(apkFileInputStream)
        var entry = zipIn.nextEntry
        while (entry != null) {
            if (entry.name == "AndroidManifest.xml") {
                return parseManifestInputStream(zipIn, entry.size, requestFetchingMinSdkVersion)
            }
            entry = zipIn.nextEntry
        }
        return null
    }

    @WorkerThread
    fun parseUri(context: Context, uri: Uri, requestFetchingMinSdkVersion: Boolean = false): SimpleApkInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val result = AtomicReference<SimpleApkInfo?>(null)
            val countDownLatch = CountDownLatch(1)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { pfd ->
                context.packageManager.parseAndroidManifest(pfd) { parser ->
                    val simpleApkInfo = extractData(parser, requestFetchingMinSdkVersion)
                    result.set(simpleApkInfo)
                    countDownLatch.countDown()
                }
            }
            countDownLatch.await()
            return result.get()
        }
        return try {
            context.contentResolver.openInputStream(uri)
                    ?.use { findAndParseManifest(it, requestFetchingMinSdkVersion) }
        } catch (e: Exception) {
            null
        }
    }

    @WorkerThread
    fun parseFile(context: Context, file: File, requestFetchingMinSdkVersion: Boolean = false): SimpleApkInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val result = AtomicReference<SimpleApkInfo?>(null)
            val countDownLatch = CountDownLatch(1)
            context.packageManager.parseAndroidManifest(file) { parser ->
                val simpleApkInfo = extractData(parser, requestFetchingMinSdkVersion)
                result.set(simpleApkInfo)
                countDownLatch.countDown()
            }
            countDownLatch.await()
            return result.get()
        }
        return try {
            FileInputStream(file).use { findAndParseManifest(it, requestFetchingMinSdkVersion) }
        } catch (e: Exception) {
            null
        }
    }

    private fun readExactBytes(input: InputStream, size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == size) bytes else null
    }

    private fun readManifestBytes(input: InputStream): ByteArray? {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var totalRead = 0
        var n: Int
        while (input.read(buffer).also { n = it } != -1) {
            totalRead += n
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }

    private fun getString(buffer: ByteBuffer, poolOffset: Int, index: Int): String? {
        if (index < 0 || poolOffset < 0) return null
        val originalPos = buffer.position()
        try {
            buffer.position(poolOffset + 8)
            val stringCount = buffer.int
            if (index >= stringCount) return null
            buffer.int // styleCount
            val flags = buffer.int
            val stringsOffset = buffer.int
            buffer.position(poolOffset + 28 + (index * 4))
            val offset = buffer.int
            buffer.position(poolOffset + stringsOffset + offset)
            return if ((flags and UTF8_FLAG) != 0) readUtf8String(buffer)
            else readUtf16String(buffer)
        } catch (e: Exception) {
            return null
        } finally {
            buffer.position(originalPos)
        }
    }

    private fun readUtf16String(buffer: ByteBuffer): String {
        val len = buffer.short.toInt() and 0xFFFF
        val bytes = ByteArray(len * 2)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_16LE)
    }

    private fun readUtf8String(buffer: ByteBuffer): String {
        var len = buffer.get().toInt() and 0xFF
        if ((len and 0x80) != 0) len = (len and 0x7F shl 8) or (buffer.get().toInt() and 0xFF)
        var byteLen = buffer.get().toInt() and 0xFF
        if ((byteLen and 0x80) != 0) byteLen =
                (byteLen and 0x7F shl 8) or (buffer.get().toInt() and 0xFF)
        val bytes = ByteArray(byteLen)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    @WorkerThread
    private fun extractData(parser: XmlPullParser, requestFetchingMinSdkVersion: Boolean): SimpleApkInfo? {
        var eventType: Int = parser.eventType
        var packageName: String? = null
        var versionCode: Long? = null
        var splitName: String? = null
        var isSplit = false
        var minSdk: Int? = null
        var foundManifest = false
        var foundUsesSdk = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "manifest") {
                    val ns = "http://schemas.android.com/apk/res/android"
                    packageName = parser.getAttributeValue(null, "package")
                            ?: parser.getAttributeValue(ns, "package")
                    if (packageName.isNullOrBlank())
                        return null
                    versionCode = parser.getAttributeValue(ns, "versionCode")?.toLongOrNull()
                            ?: return null
                    splitName = parser.getAttributeValue(null, "split")
                    // If 'split' attribute exists, it's a split APK
                    isSplit = !splitName.isNullOrEmpty()
                    foundManifest = true
                } else if (requestFetchingMinSdkVersion && parser.name == "uses-sdk") {
                    val ns = "http://schemas.android.com/apk/res/android"
                    minSdk = parser.getAttributeValue(ns, "minSdkVersion")?.toIntOrNull()
                    foundUsesSdk = true
                }
                // Break early if we got what we needed
                if (foundManifest && (!requestFetchingMinSdkVersion || foundUsesSdk)) {
                    return SimpleApkInfo(packageName, versionCode, splitName, isSplit, minSdk)
                }
            }
            eventType = parser.next()
        }
        // Return whatever we managed to find if EOF is reached and manifest was found
        return if (foundManifest) {
            SimpleApkInfo(packageName, versionCode, splitName, isSplit, minSdk)
        } else null
    }
}
