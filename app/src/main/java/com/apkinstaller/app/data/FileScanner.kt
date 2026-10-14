package com.apkinstaller.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanResult(
    val files: List<ScannedFile>,
    /** Dossiers autorisés auxquels l'accès n'est plus possible. */
    val lostFolders: List<Uri>,
    /** Android 9 et antérieur : la permission de lecture du stockage n'est pas encore accordée. */
    val needsStoragePermission: Boolean,
)

/**
 * Recherche des APK/ZIP : MediaStore (Téléchargements, quand le système l'autorise) puis dossiers
 * autorisés par l'utilisateur via le Storage Access Framework.
 */
class FileScanner(private val context: Context, private val repo: FileRepository) {
    private val resolver get() = context.contentResolver

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, ScannedFile>()
        fun add(f: ScannedFile) {
            if (found.size < MAX_FILES) found.putIfAbsent("${f.name.lowercase()}|${f.size}|${f.modifiedMs / 1000}", f)
        }

        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (!needsPermission) queryMediaStore(::add)

        val lost = ArrayList<Uri>()
        for (tree in repo.persistedTreeUris()) {
            currentCoroutineContext().ensureActive()
            if (!walkTree(tree, ::add)) lost += tree
        }
        ScanResult(found.values.toList(), lost, needsPermission)
    }

    private suspend fun queryMediaStore(add: (ScannedFile) -> Unit) {
        val base = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val nameFilter = EXTENSIONS.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?" to "Download/%"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Files.FileColumns.DATA} LIKE ?" to "%/Download/%"
        }
        val selection = "($nameFilter) AND ${location.first}"
        val args = (EXTENSIONS.map { "%.$it" } + location.second).toTypedArray()
        try {
            resolver.query(base, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val name = c.getString(nameCol) ?: continue
                    val kind = repo.kindOf(name) ?: continue
                    add(
                        ScannedFile(
                            uri = ContentUris.withAppendedId(base, c.getLong(idCol)),
                            name = name,
                            size = c.getLong(sizeCol),
                            modifiedMs = c.getLong(dateCol) * 1000,
                            kind = kind,
                        ),
                    )
                }
            }
        } catch (_: SecurityException) {
            // Pas d'accès à MediaStore pour ces fichiers : on s'appuie sur les dossiers autorisés.
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Parcourt un dossier autorisé. Renvoie false si l'accès est perdu ou le dossier a disparu. */
    private suspend fun walkTree(tree: Uri, add: (ScannedFile) -> Unit): Boolean {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(rootId to 0)
        var visited = 0
        var first = true
        while (stack.isNotEmpty() && visited < MAX_DIRS) {
            currentCoroutineContext().ensureActive()
            val (docId, depth) = stack.removeLast()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                val cursor = resolver.query(children, CHILD_COLUMNS, null, null, null)
                if (cursor == null) {
                    if (first) return false
                    continue
                }
                cursor.use { c ->
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) stack.addLast(id to depth + 1)
                            continue
                        }
                        val kind = repo.kindOf(name) ?: continue
                        add(
                            ScannedFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                name = name,
                                size = if (c.isNull(3)) -1 else c.getLong(3),
                                modifiedMs = if (c.isNull(4)) 0 else c.getLong(4),
                                kind = kind,
                            ),
                        )
                    }
                }
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            } catch (_: UnsupportedOperationException) {
                return false
            } catch (_: IllegalStateException) {
                if (first) return false
            }
            first = false
        }
        return true
    }

    private companion object {
        val EXTENSIONS = listOf("apk", "zip", "xapk", "apks", "apkm")
        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        const val MAX_FILES = 5_000
        const val MAX_DIRS = 3_000
        const val MAX_DEPTH = 8
    }
}
