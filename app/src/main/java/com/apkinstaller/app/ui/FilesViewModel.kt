package com.apkinstaller.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkinstaller.app.ApkInstallerApp
import com.apkinstaller.app.data.FileKind
import com.apkinstaller.app.data.ScannedFile
import com.apkinstaller.app.data.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FilesUiState(
    val files: List<ScannedFile> = emptyList(),
    val scanning: Boolean = false,
    val hasScanned: Boolean = false,
    val needsStoragePermission: Boolean = false,
    val notices: List<String> = emptyList(),
)

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ApkInstallerApp).container

    private val raw = MutableStateFlow<List<ScannedFile>>(emptyList())
    private val status = MutableStateFlow(FilesUiState())
    private var scanJob: Job? = null

    val state: StateFlow<FilesUiState> = combine(raw, status, c.settings.settings) { files, st, settings ->
        val filtered = if (settings.showZip) files else files.filter { it.kind == FileKind.APK }
        val sorted = when (settings.sortOrder) {
            SortOrder.DATE -> filtered.sortedByDescending { it.modifiedMs }
            SortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortOrder.SIZE -> filtered.sortedByDescending { it.size }
        }
        st.copy(files = sorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    init {
        viewModelScope.launch {
            if (c.settings.current().autoScan) refresh()
        }
    }

    fun refresh() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            status.value = status.value.copy(scanning = true)
            try {
                val result = c.scanner.scan()
                raw.value = result.files
                val notices = ArrayList<String>()

                // Dossiers autorisés précédemment mais dont l'accès a été retiré par le système/l'utilisateur.
                val persisted = c.files.persistedTreeUris().map { it.toString() }.toSet()
                val known = c.settings.current().knownFolders
                val revoked = known - persisted
                revoked.forEach { notices += "L'accès au dossier « ${c.files.folderLabel(Uri.parse(it))} » a été perdu. Autorisez-le à nouveau." }
                result.lostFolders.forEach {
                    notices += "Le dossier « ${c.files.folderLabel(it)} » n'est plus accessible (supprimé ou autorisation retirée)."
                    c.files.release(it)
                }
                val still = persisted - result.lostFolders.map { it.toString() }.toSet()
                if (still != known) c.settings.setKnownFolders(still)

                status.value = status.value.copy(
                    hasScanned = true,
                    needsStoragePermission = result.needsStoragePermission,
                    notices = notices,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = status.value.copy(hasScanned = true, notices = listOf("La recherche a échoué : ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                status.value = status.value.copy(scanning = false)
            }
        }
    }

    /** Dossier choisi dans le sélecteur Android : on mémorise l'autorisation si le système l'accorde. */
    fun onFolderPicked(uri: Uri) {
        c.files.takePersistableRead(uri)
        viewModelScope.launch {
            c.settings.setKnownFolders(c.settings.current().knownFolders + uri.toString())
            refresh()
        }
    }
}
