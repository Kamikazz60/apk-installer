package com.apkinstaller.app.install

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Extraction sécurisée d'APK depuis une archive : les fichiers sont toujours écrits sous un nom
 * généré (jamais celui de l'entrée), vérifiés (taille, CRC) et bornés par les limites.
 * L'archive d'origine n'est jamais modifiée.
 */
object ArchiveExtractor {
    fun extract(
        zip: ZipFile,
        entries: List<ApkEntry>,
        destDir: File,
        limits: ArchiveLimits = ArchiveLimits(),
        check: () -> Unit = {},
    ): List<File> {
        if (!destDir.exists() && !destDir.mkdirs()) throw InstallerException("Impossible de créer le dossier temporaire.")
        val root = destDir.canonicalFile
        var total = 0L
        val result = ArrayList<File>()
        for ((index, apk) in entries.withIndex()) {
            check()
            val zipEntry = zip.getEntry(apk.entryName)
                ?: throw InstallerException("« ${apk.displayName} » est introuvable dans l'archive.")
            if (ZipAnalyzer.isUnsafeName(zipEntry.name)) throw InstallerException("Archive refusée : chemin dangereux.")
            ZipAnalyzer.checkDeclaredSize(zipEntry, limits)

            val out = File(root, "apk_$index.apk")
            if (out.canonicalFile.parentFile != root) throw InstallerException("Archive refusée : chemin dangereux.")
            val part = File(root, "apk_$index.apk.part")
            val crc = CRC32()
            var written = 0L
            try {
                zip.getInputStream(zipEntry).use { input ->
                    part.outputStream().buffered().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            check()
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > limits.maxEntryBytes || total > limits.maxTotalExtractedBytes) {
                                throw InstallerException("Taille décompressée excessive : extraction interrompue.")
                            }
                            crc.update(buf, 0, n)
                            output.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: ZipException) {
                part.delete()
                throw InstallerException("L'archive est corrompue (« ${apk.displayName} » illisible).", e)
            } catch (e: IOException) {
                part.delete()
                throw InstallerException("Erreur pendant l'extraction de « ${apk.displayName} » : ${e.message ?: "erreur d'E/S"}", e)
            } catch (e: Throwable) {
                part.delete() // annulation ou limite dépassée : pas de fichier partiel
                throw e
            }
            if (zipEntry.size >= 0 && written != zipEntry.size) {
                part.delete()
                throw InstallerException("L'archive est corrompue ou incomplète (« ${apk.displayName} »).")
            }
            if (zipEntry.crc != -1L && crc.value != zipEntry.crc) {
                part.delete()
                throw InstallerException("L'archive est corrompue (somme de contrôle invalide pour « ${apk.displayName} »).")
            }
            if (!part.renameTo(out)) {
                part.delete()
                throw InstallerException("Impossible de finaliser l'extraction de « ${apk.displayName} ».")
            }
            result += out
        }
        return result
    }
}
