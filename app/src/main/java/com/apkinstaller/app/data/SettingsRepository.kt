package com.apkinstaller.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class SortOrder { DATE, NAME, SIZE }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val autoScan: Boolean = true,
    val sortOrder: SortOrder = SortOrder.DATE,
    val showZip: Boolean = true,
    val autoCleanCache: Boolean = true,
    /** Dossiers autorisés connus de l'application (pour détecter une perte d'accès). */
    val knownFolders: Set<String> = emptySet(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val SORT = stringPreferencesKey("sort")
        val SHOW_ZIP = booleanPreferencesKey("show_zip")
        val AUTO_CLEAN = booleanPreferencesKey("auto_clean")
        val FOLDERS = stringSetPreferencesKey("known_folders")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            AppSettings(
                theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
                autoScan = p[Keys.AUTO_SCAN] ?: true,
                sortOrder = p[Keys.SORT]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE,
                showZip = p[Keys.SHOW_ZIP] ?: true,
                autoCleanCache = p[Keys.AUTO_CLEAN] ?: true,
                knownFolders = p[Keys.FOLDERS] ?: emptySet(),
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(v: ThemeMode) = edit { it[Keys.THEME] = v.name }
    suspend fun setAutoScan(v: Boolean) = edit { it[Keys.AUTO_SCAN] = v }
    suspend fun setSortOrder(v: SortOrder) = edit { it[Keys.SORT] = v.name }
    suspend fun setShowZip(v: Boolean) = edit { it[Keys.SHOW_ZIP] = v }
    suspend fun setAutoCleanCache(v: Boolean) = edit { it[Keys.AUTO_CLEAN] = v }
    suspend fun setKnownFolders(v: Set<String>) = edit { it[Keys.FOLDERS] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
