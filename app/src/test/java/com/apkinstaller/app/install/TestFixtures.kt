package com.apkinstaller.app.install

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fabrique de AndroidManifest.xml binaire minimal (UTF-16) pour les tests. */
object AxmlBuilder {
    fun manifest(
        pkg: String,
        versionCode: Int = 1,
        versionName: String? = null,
        split: String? = null,
        feature: Boolean = false,
        configForSplit: String? = null,
    ): ByteArray {
        val strings = ArrayList<String>()
        fun s(v: String): Int = strings.indexOf(v).takeIf { it >= 0 } ?: strings.size.also { strings += v }
        s("manifest")
        class A(val name: Int, val raw: Int, val type: Int, val data: Int)
        val attrs = ArrayList<A>()
        attrs += A(s("package"), s(pkg), 0x03, s(pkg))
        attrs += A(s("versionCode"), -1, 0x10, versionCode)
        versionName?.let { attrs += A(s("versionName"), s(it), 0x03, s(it)) }
        split?.let { attrs += A(s("split"), s(it), 0x03, s(it)) }
        if (feature) attrs += A(s("isFeatureSplit"), -1, 0x12, -1)
        configForSplit?.let { attrs += A(s("configForSplit"), s(it), 0x03, s(it)) }

        // Chunk des chaînes
        val pool = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (str in strings) {
            offsets += pool.size()
            val b = ByteBuffer.allocate(2 + str.length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(str.length.toShort())
            b.put(str.toByteArray(Charsets.UTF_16LE))
            b.putShort(0)
            pool.write(b.array())
        }
        while (pool.size() % 4 != 0) pool.write(0)
        val poolHeader = 28 + 4 * strings.size
        val poolChunk = ByteBuffer.allocate(poolHeader + pool.size()).order(ByteOrder.LITTLE_ENDIAN)
        poolChunk.putShort(0x0001).putShort(28).putInt(poolChunk.capacity())
        poolChunk.putInt(strings.size).putInt(0).putInt(0).putInt(poolHeader).putInt(0)
        offsets.forEach { poolChunk.putInt(it) }
        poolChunk.put(pool.toByteArray())

        // Élément <manifest>
        val el = ByteBuffer.allocate(36 + 20 * attrs.size).order(ByteOrder.LITTLE_ENDIAN)
        el.putShort(0x0102).putShort(16).putInt(el.capacity()).putInt(1).putInt(-1)
        el.putInt(-1).putInt(0) // ns, name = "manifest"
        el.putShort(20).putShort(20).putShort(attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
        for (a in attrs) {
            el.putInt(-1).putInt(a.name).putInt(a.raw).putShort(8).put(0).put(a.type.toByte()).putInt(a.data)
        }

        val total = 8 + poolChunk.capacity() + el.capacity()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003).putShort(8).putInt(total)
        out.put(poolChunk.array()).put(el.array())
        return out.array()
    }
}

object ZipFixtures {
    /** Construit un faux APK (archive contenant un AndroidManifest.xml binaire valide). */
    fun apkBytes(
        pkg: String,
        versionCode: Int = 1,
        split: String? = null,
        feature: Boolean = false,
        configForSplit: String? = null,
        padding: Int = 0,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml"))
            z.write(AxmlBuilder.manifest(pkg, versionCode, "1.0", split, feature, configForSplit))
            z.closeEntry()
            if (padding > 0) {
                z.putNextEntry(ZipEntry("classes.dex"))
                z.write(ByteArray(padding).also { java.util.Random(1).nextBytes(it) }) // incompressible
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun zip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
    }

    fun entry(name: String, pkg: String, versionCode: Long = 1, split: String? = null, feature: Boolean = false) = ApkEntry(
        entryName = name,
        size = 1000,
        compressedSize = 500,
        manifest = ManifestInfo(pkg, versionCode, "1.0", split, feature, null),
    )
}
