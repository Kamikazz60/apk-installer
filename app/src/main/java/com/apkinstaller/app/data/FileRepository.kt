package com.apkinstaller.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.apkinstaller.app.install.InstallerException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

enum class FileKind { APK, ARCHIVE }

data class ScannedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modifiedMs: Long,
    val kind: FileKind,
)

data class FileMeta(val name: String, val size: Long)

/** Accès aux documents via ContentResolver : aucun URI n'est supposé être un chemin de fichier. */
class FileRepository(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun kindOf(name: String): FileKind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".apk") -> FileKind.APK
            n.endsWith(".zip") || n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm") -> FileKind.ARCHIVE
            else -> null
        }
    }

    /** Nom et taille d'un URI ; tolère les fournisseurs qui ne renseignent pas ces colonnes. */
    fun queryMeta(uri: Uri): FileMeta {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        } catch (_: Exception) {
            // Fournisseur capricieux : on se rabat sur l'URI.
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
        return FileMeta(name?.takeIf { it.isNotBlank() } ?: fallback ?: "fichier", size)
    }

    /** Copie le contenu de [uri] vers [dest] (stockage privé). Renvoie le nombre d'octets copiés. */
    suspend fun copyToCache(uri: Uri, dest: File, expectedSize: Long, maxBytes: Long): Long {
        dest.parentFile?.mkdirs()
        if (expectedSize > maxBytes) throw InstallerException("Le fichier est trop volumineux pour être traité.")
        val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (expectedSize > 0 && expectedSize + 32L * 1024 * 1024 > free) {
            throw InstallerException("Espace de stockage insuffisant pour traiter ce fichier.")
        }
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            throw InstallerException("Le fichier est introuvable ou n'est plus accessible.", e)
        } catch (e: SecurityException) {
            throw InstallerException("L'accès à ce fichier n'est pas autorisé.", e)
        } ?: throw InstallerException("Impossible d'ouvrir ce fichier.")
        var total = 0L
        try {
            input.use { src ->
                dest.outputStream().buffered().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw InstallerException("Le fichier est trop volumineux pour être traité.")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: IOException) {
            dest.delete()
            throw InstallerException("Lecture du fichier impossible : ${e.message ?: "erreur d'E/S"}", e)
        } catch (e: SecurityException) {
            dest.delete()
            throw InstallerException("L'accès à ce fichier n'est pas autorisé.", e)
        }
        if (total == 0L) throw InstallerException("Le fichier est vide.")
        return total
    }

    fun persistedTreeUris(): List<Uri> =
        resolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri }

    fun takePersistableRead(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Le système n'accorde pas de permission persistante : l'accès durera le temps de la session.
        }
    }

    fun release(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    fun folderLabel(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?.substringAfter(':')?.ifBlank { "Stockage interne" } ?: tree.toString()
}
