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
     */
    data class SimpleApkInfo(
            val packageName: String?,
            val versionCode: Long?,
            val splitName: String?,
            val isSplit: Boolean
    )

    /**
     * Parses the manifest from an [InputStream].
     *
     * @param manifestEntryInputStream The stream positioned at the start of AndroidManifest.xml
     * @param entrySize The size of the entry if known, else -1
     * @return [SimpleApkInfo] if successful, null otherwise.
     */
    @WorkerThread
    fun parseManifestInputStream(manifestEntryInputStream: InputStream, entrySize: Long = -1L): SimpleApkInfo? {
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
        var info: SimpleApkInfo? = null
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
                    val tagInfo = parseStartTag(buffer, stringPoolOffset)
                    if (tagInfo != null && tagInfo.first == "manifest") {
                        info = tagInfo.second
                        break
                    }
                }
            }
            val nextPos = chunkPos + chunkSize
            if (nextPos > buffer.limit() || nextPos < 0) break
            buffer.position(nextPos)
        }
        return info
    }

    fun findAndParseManifest(apkFileInputStream: InputStream): SimpleApkInfo? {
        // Switching to Apache ZipArchiveInputStream for speed
        val zipIn = ZipArchiveInputStream(apkFileInputStream)
        var entry = zipIn.nextEntry
        while (entry != null) {
            if (entry.name == "AndroidManifest.xml") {
                return parseManifestInputStream(zipIn, entry.size)
            }
            entry = zipIn.nextEntry
        }
        return null
    }

    @WorkerThread
    fun parseUri(context: Context, uri: Uri): SimpleApkInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val result = AtomicReference<SimpleApkInfo?>(null)
            val countDownLatch = CountDownLatch(1)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { pfd ->
                context.packageManager.parseAndroidManifest(pfd) { parser ->
                    val simpleApkInfo = extractData(parser)
                    result.set(simpleApkInfo)
                    countDownLatch.countDown()
                }
            }
            countDownLatch.await()
            return result.get()
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { findAndParseManifest(it) }
        } catch (e: Exception) {
            null
        }
    }

    @WorkerThread
    fun parseFile(context: Context, file: File): SimpleApkInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val result = AtomicReference<SimpleApkInfo?>(null)
            val countDownLatch = CountDownLatch(1)
            context.packageManager.parseAndroidManifest(file) { parser ->
                val simpleApkInfo = extractData(parser)
                result.set(simpleApkInfo)
                countDownLatch.countDown()
            }
            countDownLatch.await()
            return result.get()
        }
        return try {
            FileInputStream(file).use { findAndParseManifest(it) }
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

    private fun parseStartTag(buffer: ByteBuffer, poolOffset: Int): Pair<String, SimpleApkInfo>? {
        buffer.int // line
        buffer.int // comment
        buffer.int // ns
        val nameIndex = buffer.int
        val tagName = getString(buffer, poolOffset, nameIndex)
        if (tagName != "manifest") return null
        buffer.short // attrStart
        buffer.short // attrSize
        val attrCount = buffer.short.toInt() and 0xFFFF
        var pkg: String? = null
        var vCode: Long? = null
        var split: String? = null
        buffer.position(buffer.position() + 6) // Skip classIndex + padding
        for (i in 0 until attrCount) {
            buffer.int // ns
            val attrNameIndex = buffer.int
            buffer.int // rawValue
            buffer.int // typedValueType
            val data = buffer.int
            val attrName = getString(buffer, poolOffset, attrNameIndex)
            when (attrName) {
                "package" -> pkg = getString(buffer, poolOffset, data)
                "versionCode" -> vCode = data.toLong()
                "split" -> split = getString(buffer, poolOffset, data)
            }
        }
        return "manifest" to SimpleApkInfo(pkg, vCode, split, !split.isNullOrEmpty())
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
        if ((byteLen and 0x80) != 0) byteLen = (byteLen and 0x7F shl 8) or (buffer.get().toInt() and 0xFF)
        val bytes = ByteArray(byteLen)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    @WorkerThread
    private fun extractData(parser: XmlPullParser): SimpleApkInfo {
        var packageName: String? = null
        var versionCode: Long? = null
        var splitName: String? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "manifest") {
                val ns = "http://schemas.android.com/apk/res/android"
                packageName = parser.getAttributeValue(null, "package") ?: parser.getAttributeValue(
                        ns,
                        "package"
                )
                versionCode = parser.getAttributeValue(ns, "versionCode")?.toLongOrNull()
                // If 'split' attribute exists, it's a split APK
                splitName = parser.getAttributeValue(null, "split")
                break
            }
            eventType = parser.next()
        }
        val simpleApkInfo = SimpleApkInfo(packageName = packageName, versionCode = versionCode,
                splitName = splitName, isSplit = !splitName.isNullOrEmpty())
        return simpleApkInfo
    }

}
