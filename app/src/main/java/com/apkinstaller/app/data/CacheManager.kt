package com.apkinstaller.app.data

import android.content.Context
import java.io.File
import java.util.UUID

/** Stockage temporaire privé (cache de l'application) : jamais de fichiers de l'utilisateur ici sauf des copies de travail. */
class CacheManager(context: Context) {
    private val root = File(context.cacheDir, "work")

    /** Crée un dossier de travail neuf ; les précédents sont supprimés. */
    @Synchronized
    fun newSession(): File {
        clear()
        return File(root, UUID.randomUUID().toString()).also { it.mkdirs() }
    }

    @Synchronized
    fun clear(): Long {
        val size = size()
        root.deleteRecursively()
        return size
    }

    fun size(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(dir: File?) {
        if (dir != null && dir.canonicalPath.startsWith(root.canonicalPath)) dir.deleteRecursively()
    }
}
