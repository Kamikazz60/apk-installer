package com.apkinstaller.app.install

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ZipScan(
    val apks: List<ApkEntry>,
    /** Entrées .apk illisibles ou qui n'en sont pas réellement (extension trompeuse). */
    val invalid: List<String>,
    val totalEntries: Int,
)

/** Analyse d'une archive ZIP sans l'extraire : seuls les manifestes des APK sont lus, en flux. */
object ZipAnalyzer {
    /** Un APK est une archive ZIP qui contient AndroidManifest.xml à sa racine. */
    fun isApk(zip: ZipFile): Boolean = zip.getEntry("AndroidManifest.xml") != null

    fun isUnsafeName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains('\u0000')) return true
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return true
        return name.split('/', '\\').any { it == ".." }
    }

    fun open(file: File): ZipFile = try {
        ZipFile(file)
    } catch (e: IOException) {
        if (e.message?.contains("path", ignoreCase = true) == true && e.message?.contains("entry", ignoreCase = true) == true) {
            throw InstallerException("Archive refusée : elle contient un chemin dangereux (tentative de type Zip Slip).", e)
        }
        throw InstallerException("L'archive est corrompue ou incomplète.", e)
    } catch (e: RuntimeException) {
        throw InstallerException("L'archive est invalide ou contient des noms de fichiers dangereux.", e)
    }

    fun analyze(
        zip: ZipFile,
        limits: ArchiveLimits = ArchiveLimits(),
        tempDir: File? = null,
        check: () -> Unit = {},
    ): ZipScan {
        val total = try {
            zip.size()
        } catch (e: IllegalStateException) {
            throw InstallerException("L'archive est corrompue.", e)
        }
        if (total > limits.maxEntries) {
            throw InstallerException("L'archive contient trop de fichiers ($total, maximum ${limits.maxEntries}).")
        }
        val names = HashSet<String>()
        val candidates = ArrayList<ZipEntry>()
        var declaredTotal = 0L
        try {
            val e = zip.entries()
            while (e.hasMoreElements()) {
                check()
                val entry = e.nextElement()
                if (isUnsafeName(entry.name)) {
                    throw InstallerException("Archive refusée : elle contient un chemin dangereux (« ${entry.name.take(80)} »).")
                }
                if (!names.add(entry.name)) {
                    throw InstallerException("Archive refusée : elle contient des entrées en double (« ${entry.name.take(80)} »).")
                }
                if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) continue
                candidates += entry
                if (candidates.size > limits.maxApkEntries) {
                    throw InstallerException("L'archive contient trop d'APK (maximum ${limits.maxApkEntries}).")
                }
                checkDeclaredSize(entry, limits)
                declaredTotal += entry.size
                if (declaredTotal > limits.maxTotalExtractedBytes) {
                    throw InstallerException("Taille décompressée excessive : l'archive dépasse la limite autorisée.")
                }
            }
        } catch (e: ZipException) {
            throw InstallerException("L'archive est corrompue.", e)
        }

        val apks = ArrayList<ApkEntry>()
        val invalid = ArrayList<String>()
        for (entry in candidates) {
            check()
            val manifest = try {
                readManifest(zip, entry, limits, tempDir, check)
            } catch (e: IOException) {
                null
            }
            if (manifest == null) invalid += entry.name
            else apks += ApkEntry(entry.name, entry.size, entry.compressedSize, manifest)
        }
        return ZipScan(apks, invalid, total)
    }

    internal fun checkDeclaredSize(entry: ZipEntry, limits: ArchiveLimits) {
        val size = entry.size
        if (size < 0) throw InstallerException("L'archive est corrompue (taille d'entrée inconnue).")
        if (size > limits.maxEntryBytes) {
            throw InstallerException("Taille décompressée excessive : « ${entry.name.substringAfterLast('/')} » dépasse la limite autorisée.")
        }
        val compressed = entry.compressedSize
        if (size > limits.ratioCheckMinBytes && compressed > 0 && size / compressed > limits.maxCompressionRatio) {
            throw InstallerException("Archive suspecte (taux de compression anormal, possible ZIP bomb) : extraction refusée.")
        }
    }

    /** Lit AndroidManifest.xml dans l'APK imbriqué sans l'écrire sur le disque. Renvoie null si ce n'est pas un APK. */
    private fun readManifest(zip: ZipFile, entry: ZipEntry, limits: ArchiveLimits, tempDir: File?, check: () -> Unit): ManifestInfo? {
        val bytes = try {
            streamManifest(zip.getInputStream(entry), limits, check)
        } catch (e: ZipException) {
            // Cas rare (entrée STORED avec descriptor) : repli via un fichier temporaire privé.
            if (tempDir == null) null else manifestViaTempFile(zip, entry, limits, tempDir, check)
        }
        return bytes?.let(AxmlParser::parseManifest)
    }

    private fun streamManifest(raw: InputStream, limits: ArchiveLimits, check: () -> Unit): ByteArray? {
        var scanned = 0L
        ZipInputStream(raw).use { zin ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                check()
                val e = zin.nextEntry ?: return null
                if (e.name != "AndroidManifest.xml") {
                    // On avance dans le flux ; la quantité lue est bornée.
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        scanned += n
                        if (scanned > limits.maxManifestScanBytes) return null
                    }
                    continue
                }
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = zin.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limits.maxManifestBytes) return null
                }
                return out.toByteArray()
            }
        }
    }

    private fun manifestViaTempFile(zip: ZipFile, entry: ZipEntry, limits: ArchiveLimits, tempDir: File, check: () -> Unit): ByteArray? {
        tempDir.mkdirs()
        val tmp = File.createTempFile("probe", ".apk", tempDir)
        try {
            var written = 0L
            zip.getInputStream(entry).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > limits.maxEntryBytes) return null
                        out.write(buf, 0, n)
                    }
                }
            }
            ZipFile(tmp).use { inner ->
                val m = inner.getEntry("AndroidManifest.xml") ?: return null
                if (m.size < 0 || m.size > limits.maxManifestBytes) return null
                return inner.getInputStream(m).use { it.readBytes() }
            }
        } catch (e: IOException) {
            return null
        } finally {
            tmp.delete()
        }
    }
}
