package com.apkinstaller.app.install

import com.apkinstaller.app.install.ZipFixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitSelectorTest {
    private val device = DeviceSpec(abis = listOf("arm64-v8a", "armeabi-v7a"), densityDpi = 420, languages = listOf("fr", "en"))

    private fun ok(entries: List<ApkEntry>) = SplitSelector.group(entries, device) as GroupResult.Ok

    @Test
    fun `plusieurs applications independantes donnent plusieurs groupes`() {
        val r = ok(listOf(entry("a.apk", "com.a"), entry("b.apk", "com.b")))
        assertEquals(2, r.groups.size)
        assertTrue(r.groups.none { it.isSplitApp })
    }

    @Test
    fun `application fractionnee garde base et splits compatibles uniquement`() {
        val r = ok(
            listOf(
                entry("base.apk", "com.x"),
                entry("config.arm64_v8a.apk", "com.x", split = "config.arm64_v8a"),
                entry("config.armeabi_v7a.apk", "com.x", split = "config.armeabi_v7a"),
                entry("config.x86.apk", "com.x", split = "config.x86"),
                entry("config.xxhdpi.apk", "com.x", split = "config.xxhdpi"),
                entry("config.hdpi.apk", "com.x", split = "config.hdpi"),
                entry("config.fr.apk", "com.x", split = "config.fr"),
                entry("config.de.apk", "com.x", split = "config.de"),
            ),
        )
        val g = r.groups.single()
        assertEquals("base.apk", g.base.entryName)
        val names = g.splits.map { it.manifest.splitName }.toSet()
        // ABI préférée (arm64), densité la plus proche de 420 dpi (xxhdpi = 480), langue du système
        assertEquals(setOf("config.arm64_v8a", "config.xxhdpi", "config.fr"), names)
        assertEquals(4, r.skipped.size)
    }

    @Test
    fun `module de fonctionnalite est conserve et ses configs suivent`() {
        val r = ok(
            listOf(
                entry("base.apk", "com.x"),
                entry("feat.apk", "com.x", split = "feat", feature = true),
                entry("feat.config.arm64_v8a.apk", "com.x", split = "feat.config.arm64_v8a"),
                entry("orphan.config.arm64_v8a.apk", "com.x", split = "ghost.config.arm64_v8a"),
            ),
        )
        val names = r.groups.single().splits.map { it.manifest.splitName }
        assertEquals(listOf("feat", "feat.config.arm64_v8a"), names)
        assertEquals(1, r.skipped.size) // split d'un module absent
    }

    @Test
    fun `aucun split d'architecture compatible est une erreur`() {
        val r = SplitSelector.group(
            listOf(entry("base.apk", "com.x"), entry("config.x86.apk", "com.x", split = "config.x86")),
            device,
        )
        assertTrue(r is GroupResult.Error)
    }

    @Test
    fun `base absente est une erreur`() {
        val r = SplitSelector.group(listOf(entry("s.apk", "com.x", split = "config.fr")), device)
        assertTrue(r is GroupResult.Error)
    }

    @Test
    fun `deux bases pour le meme paquet sont ambigues`() {
        val r = SplitSelector.group(listOf(entry("a.apk", "com.x", 1), entry("b.apk", "com.x", 2)), device)
        assertTrue(r is GroupResult.Error)
    }

    @Test
    fun `version de split differente de la base est refusee`() {
        val r = SplitSelector.group(
            listOf(entry("base.apk", "com.x", 5), entry("c.apk", "com.x", 4, split = "config.fr")),
            device,
        )
        assertTrue(r is GroupResult.Error)
    }

    @Test
    fun `splits d'un autre paquet ne sont jamais melanges`() {
        val r = ok(
            listOf(
                entry("base.apk", "com.x"),
                entry("c.apk", "com.x", split = "config.fr"),
                entry("other.apk", "com.y"),
            ),
        )
        assertEquals(2, r.groups.size)
        assertEquals(1, r.groups.first { it.packageName == "com.x" }.splits.size)
        assertEquals(0, r.groups.first { it.packageName == "com.y" }.splits.size)
    }

    @Test
    fun `comparaison de versions`() {
        assertEquals(VersionComparison.NOT_INSTALLED, VersionLogic.compare(null, 3))
        assertEquals(VersionComparison.NEWER, VersionLogic.compare(2, 3))
        assertEquals(VersionComparison.SAME, VersionLogic.compare(3, 3))
        assertEquals(VersionComparison.OLDER, VersionLogic.compare(4, 3))
    }
}
