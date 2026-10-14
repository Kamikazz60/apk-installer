package com.apkinstaller.app.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ZipAnalyzerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun analyze(file: File, limits: ArchiveLimits = ArchiveLimits()): ZipScan =
        ZipAnalyzer.open(file).use { ZipAnalyzer.analyze(it, limits) }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("InstallerException attendue")
        } catch (e: InstallerException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `manifeste binaire est analyse`() {
        val m = AxmlParser.parseManifest(AxmlBuilder.manifest("com.demo", 42, "2.1", split = "config.fr", configForSplit = ""))
        assertNotNull(m)
        assertEquals("com.demo", m!!.packageName)
        assertEquals(42L, m.versionCode)
        assertEquals("2.1", m.versionName)
        assertEquals("config.fr", m.splitName)
        assertFalse(m.isFeatureSplit)
    }

    @Test
    fun `manifeste tronque ou invalide donne null`() {
        val ok = AxmlBuilder.manifest("com.demo")
        assertNull(AxmlParser.parseManifest(ok.copyOf(ok.size / 2)))
        assertNull(AxmlParser.parseManifest(ByteArray(64) { 7 }))
        assertNull(AxmlParser.parseManifest(ByteArray(0)))
    }

    @Test
    fun `zip avec un seul apk`() {
        val f = tmp.newFile("one.zip")
        ZipFixtures.zip(f, listOf("app/demo.apk" to ZipFixtures.apkBytes("com.demo", 3), "readme.txt" to "hi".toByteArray()))
        val scan = analyze(f)
        assertEquals(1, scan.apks.size)
        assertEquals("com.demo", scan.apks[0].manifest.packageName)
        assertEquals("app/demo.apk", scan.apks[0].entryName)
        assertTrue(scan.invalid.isEmpty())
    }

    @Test
    fun `zip sans apk`() {
        val f = tmp.newFile("none.zip")
        ZipFixtures.zip(f, listOf("a.txt" to "x".toByteArray()))
        assertTrue(analyze(f).apks.isEmpty())
    }

    @Test
    fun `fichier apk qui n'en est pas un est signale comme invalide`() {
        val f = tmp.newFile("fake.zip")
        ZipFixtures.zip(f, listOf("fake.apk" to "pas un zip".toByteArray(), "ok.apk" to ZipFixtures.apkBytes("com.ok")))
        val scan = analyze(f)
        assertEquals(listOf("fake.apk"), scan.invalid)
        assertEquals(1, scan.apks.size)
    }

    @Test
    fun `un apk est reconnu par son contenu`() {
        val apk = tmp.newFile("x.zip").also { it.writeBytes(ZipFixtures.apkBytes("com.demo")) }
        val archive = tmp.newFile("y.apk").also { ZipFixtures.zip(it, listOf("a.apk" to ZipFixtures.apkBytes("com.demo"))) }
        assertTrue(ZipAnalyzer.open(apk).use { ZipAnalyzer.isApk(it) })
        assertFalse(ZipAnalyzer.open(archive).use { ZipAnalyzer.isApk(it) }) // extension trompeuse
    }

    @Test
    fun `zip slip est refuse`() {
        val f = tmp.newFile("slip.zip")
        ZipFixtures.zip(f, listOf("../../evil.apk" to ZipFixtures.apkBytes("com.evil")))
        assertRejected { analyze(f) }
    }

    @Test
    fun `chemins absolus et lettres de lecteur sont dangereux`() {
        assertTrue(ZipAnalyzer.isUnsafeName("/etc/passwd"))
        assertTrue(ZipAnalyzer.isUnsafeName("C:\\x.apk"))
        assertTrue(ZipAnalyzer.isUnsafeName("a/../../b.apk"))
        assertTrue(ZipAnalyzer.isUnsafeName("a\\..\\b.apk"))
        assertFalse(ZipAnalyzer.isUnsafeName("dir/sub/app.apk"))
        assertFalse(ZipAnalyzer.isUnsafeName("my..app.apk"))
    }

    @Test
    fun `archive corrompue est refusee proprement`() {
        val f = tmp.newFile("bad.zip")
        ZipFixtures.zip(f, listOf("a.apk" to ZipFixtures.apkBytes("com.a")))
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 30)) // fin de l'archive (répertoire central) coupée
        assertRejected { analyze(f) }
        val junk = tmp.newFile("junk.zip").also { it.writeBytes(ByteArray(500) { 1 }) }
        assertRejected { analyze(junk) }
    }

    @Test
    fun `trop d'entrees`() {
        val f = tmp.newFile("many.zip")
        ZipFixtures.zip(f, (1..20).map { "f$it.txt" to ByteArray(1) })
        assertRejected { analyze(f, ArchiveLimits(maxEntries = 10)) }
    }

    @Test
    fun `taille decompressee excessive`() {
        val f = tmp.newFile("big.zip")
        ZipFixtures.zip(f, listOf("big.apk" to ZipFixtures.apkBytes("com.big", padding = 200_000)))
        assertRejected { analyze(f, ArchiveLimits(maxEntryBytes = 10_000)) }
        assertRejected { analyze(f, ArchiveLimits(maxTotalExtractedBytes = 10_000)) }
    }

    @Test
    fun `zip bomb a fort taux de compression est refuse`() {
        val f = tmp.newFile("bomb.zip")
        // 5 Mo de zéros se compressent à quelques Ko : ratio très élevé.
        ZipFixtures.zip(f, listOf("bomb.apk" to ByteArray(5_000_000)))
        assertRejected { analyze(f, ArchiveLimits(ratioCheckMinBytes = 1_000_000, maxCompressionRatio = 100)) }
    }
}
