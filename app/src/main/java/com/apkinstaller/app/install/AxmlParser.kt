package com.apkinstaller.app.install

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lecteur minimal du AndroidManifest.xml binaire (AXML) : ne lit que l'élément `<manifest>`.
 * Suffisant pour connaître le paquet, la version et la nature d'un split sans extraire l'APK.
 */
object AxmlParser {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL = 0x0001
    private const val RES_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    private class Attr(val raw: Int, val type: Int, val data: Int)

    fun parseManifest(bytes: ByteArray): ManifestInfo? = try {
        parse(bytes)
    } catch (e: RuntimeException) {
        null // IndexOutOfBounds, BufferUnderflow… : fichier tronqué ou invalide
    }

    private fun parse(bytes: ByteArray): ManifestInfo? {
        if (bytes.size < 8) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (u16(bb, 0) != RES_XML_TYPE) return null
        var pos = u16(bb, 2)
        var strings: List<String>? = null
        while (pos + 8 <= bytes.size) {
            val type = u16(bb, pos)
            val size = bb.getInt(pos + 4)
            if (size < 8 || pos.toLong() + size > bytes.size) return null
            when (type) {
                RES_STRING_POOL -> strings = readStringPool(bb, pos, size)
                RES_START_ELEMENT -> return readManifest(bb, pos, strings ?: return null)
            }
            pos += size
        }
        return null
    }

    private fun u16(bb: ByteBuffer, at: Int) = bb.getShort(at).toInt() and 0xFFFF

    private fun readStringPool(bb: ByteBuffer, pos: Int, chunkSize: Int): List<String> {
        val headerSize = u16(bb, pos + 2)
        val count = bb.getInt(pos + 8)
        val flags = bb.getInt(pos + 16)
        val stringsStart = bb.getInt(pos + 20)
        require(count in 0..(chunkSize / 4)) { "string pool invalide" }
        val utf8 = flags and UTF8_FLAG != 0
        return List(count) { i ->
            val offset = bb.getInt(pos + headerSize + 4 * i)
            decodeString(bb, pos + stringsStart + offset, utf8)
        }
    }

    private fun decodeString(bb: ByteBuffer, start: Int, utf8: Boolean): String {
        var p = start
        if (utf8) {
            fun len8(): Int {
                val b = bb.get(p).toInt() and 0xFF
                return if (b and 0x80 != 0) {
                    val l = ((b and 0x7F) shl 8) or (bb.get(p + 1).toInt() and 0xFF)
                    p += 2
                    l
                } else {
                    p += 1
                    b
                }
            }
            len8() // longueur en caractères
            val byteLen = len8()
            val out = ByteArray(byteLen)
            for (i in 0 until byteLen) out[i] = bb.get(p + i)
            return String(out, Charsets.UTF_8)
        }
        var len = u16(bb, p)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(bb, p)
            p += 2
        }
        val out = ByteArray(len * 2)
        for (i in out.indices) out[i] = bb.get(p + i)
        return String(out, Charsets.UTF_16LE)
    }

    private fun readManifest(bb: ByteBuffer, pos: Int, strings: List<String>): ManifestInfo? {
        val ext = pos + 16
        val name = strings.getOrNull(bb.getInt(ext + 4))
        if (name != "manifest") return null
        val attrStart = u16(bb, ext + 8)
        val attrSize = u16(bb, ext + 10)
        val attrCount = u16(bb, ext + 12)
        val attrs = HashMap<String, Attr>()
        for (i in 0 until attrCount) {
            val a = ext + attrStart + i * attrSize
            val attrName = strings.getOrNull(bb.getInt(a + 4)) ?: continue
            attrs[attrName] = Attr(bb.getInt(a + 8), bb.get(a + 15).toInt() and 0xFF, bb.getInt(a + 16))
        }

        fun str(key: String): String? {
            val a = attrs[key] ?: return null
            return when {
                a.raw >= 0 -> strings.getOrNull(a.raw)
                a.type == TYPE_STRING -> strings.getOrNull(a.data)
                else -> null
            }
        }

        fun intVal(key: String): Long? =
            attrs[key]?.takeIf { it.raw < 0 && it.type != TYPE_STRING }?.data?.toLong()?.and(0xFFFFFFFFL)

        val pkg = str("package")?.takeIf { it.isNotEmpty() } ?: return null
        val minor = intVal("versionCode") ?: 0L
        val major = intVal("versionCodeMajor") ?: 0L
        val featureAttr = attrs["isFeatureSplit"]
        return ManifestInfo(
            packageName = pkg,
            versionCode = (major shl 32) or minor,
            versionName = str("versionName"),
            splitName = str("split"),
            isFeatureSplit = featureAttr != null && featureAttr.type == TYPE_INT_BOOLEAN && featureAttr.data != 0,
            configForSplit = str("configForSplit"),
        )
    }
}
