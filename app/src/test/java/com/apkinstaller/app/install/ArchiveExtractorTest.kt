package com.apkinstaller.app.install

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class ArchiveExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun scan(zipFile: File) = ZipAnalyzer.open(zipFile).use { ZipAnalyzer.analyze(it) }

    @Test
    fun `extraction ecrit sous un nom genere et conserve le contenu`() {
        val apk = ZipFixtures.apkBytes("com.demo", 7)
        val f = tmp.newFile("a.zip").also { ZipFixtures.zip(it, listOf("deep/dir/Weird Name é.apk" to apk)) }
        val out = tmp.newFolder("out")
        val files = ZipAnalyzer.open(f).use { z -> ArchiveExtractor.extract(z, scan(f).apks, out) }
        assertEquals(1, files.size)
        assertEquals("apk_0.apk", files[0].name)
        assertEquals(out.canonicalFile, files[0].canonicalFile.parentFile)
        assertArrayEquals(apk, files[0].readBytes())
        assertEquals(1, out.listFiles()!!.size) // aucun fichier .part résiduel
    }

    @Test
    fun `une entree qui depasse la limite interrompt l'extraction sans fichier partiel`() {
        val f = tmp.newFile("a.zip").also { ZipFixtures.zip(it, listOf("a.apk" to ZipFixtures.apkBytes("com.a", padding = 100_000))) }
        val out = tmp.newFolder("out")
        val apks = scan(f).apks
        try {
            ZipAnalyzer.open(f).use { ArchiveExtractor.extract(it, apks, out, ArchiveLimits(maxEntryBytes = 5_000)) }
            fail()
        } catch (e: InstallerException) {
            assertTrue(out.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `annulation supprime le fichier partiel`() {
        val f = tmp.newFile("a.zip").also { ZipFixtures.zip(it, listOf("a.apk" to ZipFixtures.apkBytes("com.a", padding = 300_000))) }
        val out = tmp.newFolder("out")
        val apks = scan(f).apks
        var calls = 0
        try {
            ZipAnalyzer.open(f).use {
                ArchiveExtractor.extract(it, apks, out) { if (++calls > 2) throw CancellationException("stop") }
            }
            fail()
        } catch (e: CancellationException) {
            assertTrue(out.listFiles()!!.none { it.exists() && it.name.endsWith(".part") })
            assertFalse(File(out, "apk_0.apk").exists())
        }
    }

    @Test
    fun `plusieurs apk dont les splits sont extraits dans l'ordre`() {
        val f = tmp.newFile("s.zip").also {
            ZipFixtures.zip(
                it,
                listOf(
                    "base.apk" to ZipFixtures.apkBytes("com.x", 2),
                    "split_config.fr.apk" to ZipFixtures.apkBytes("com.x", 2, split = "config.fr"),
                ),
            )
        }
        val out = tmp.newFolder("out")
        val apks = scan(f).apks
        val device = DeviceSpec(listOf("arm64-v8a"), 420, listOf("fr"))
        val group = (SplitSelector.group(apks, device) as GroupResult.Ok).groups.single()
        val files = ZipAnalyzer.open(f).use { ArchiveExtractor.extract(it, group.entries, out) }
        assertEquals(2, files.size)
        assertEquals(group.base.entryName, "base.apk")
        assertEquals("config.fr", group.splits.single().manifest.splitName)
    }

    @Test
    fun `entree absente est une erreur claire`() {
        val f = tmp.newFile("a.zip").also { ZipFixtures.zip(it, listOf("a.apk" to ZipFixtures.apkBytes("com.a"))) }
        val ghost = ZipFixtures.entry("ghost.apk", "com.a")
        try {
            ZipAnalyzer.open(f).use { ArchiveExtractor.extract(it, listOf(ghost), tmp.newFolder("o")) }
            fail()
        } catch (e: InstallerException) {
            assertTrue(e.message!!.contains("ghost.apk"))
        }
    }
}
