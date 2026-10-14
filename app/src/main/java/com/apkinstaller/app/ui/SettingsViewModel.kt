package com.apkinstaller.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkinstaller.app.ApkInstallerApp
import com.apkinstaller.app.data.AppSettings
import com.apkinstaller.app.data.SortOrder
import com.apkinstaller.app.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ApkInstallerApp).container

    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    private val _folders = MutableStateFlow<List<Pair<Uri, String>>>(emptyList())
    val folders: StateFlow<List<Pair<Uri, String>>> = _folders.asStateFlow()

    val versionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun refreshInfo() {
        viewModelScope.launch {
            _cacheSize.value = withContext(Dispatchers.IO) { c.cache.size() }
            _folders.value = c.files.persistedTreeUris().map { it to c.files.folderLabel(it) }
        }
    }

    fun setTheme(v: ThemeMode) = viewModelScope.launch { c.settings.setTheme(v) }
    fun setAutoScan(v: Boolean) = viewModelScope.launch { c.settings.setAutoScan(v) }
    fun setSort(v: SortOrder) = viewModelScope.launch { c.settings.setSortOrder(v) }
    fun setShowZip(v: Boolean) = viewModelScope.launch { c.settings.setShowZip(v) }
    fun setAutoClean(v: Boolean) = viewModelScope.launch { c.settings.setAutoCleanCache(v) }

    /** Renvoie le nombre d'octets libérés. */
    fun clearCache(onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { c.cache.clear() }
            _cacheSize.value = 0
            onDone(freed)
        }
    }

    fun removeFolder(uri: Uri) {
        viewModelScope.launch {
            c.files.release(uri)
            c.settings.setKnownFolders(c.settings.current().knownFolders - uri.toString())
            refreshInfo()
        }
    }
}
