package com.apkinstaller.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkinstaller.app.data.SortOrder
import com.apkinstaller.app.data.ThemeMode

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshInfo() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("Thème")
        RadioGroup(
            options = listOf(ThemeMode.DARK to "Sombre", ThemeMode.LIGHT to "Clair", ThemeMode.SYSTEM to "Système"),
            selected = s.theme,
            onSelect = vm::setTheme,
        )
        HorizontalDivider()

        SectionTitle("Recherche des fichiers")
        SwitchRow("Recherche automatique au lancement", s.autoScan, vm::setAutoScan)
        SwitchRow("Afficher les fichiers ZIP", s.showZip, vm::setShowZip)
        Text("Tri des fichiers", style = MaterialTheme.typography.bodyMedium)
        RadioGroup(
            options = listOf(SortOrder.DATE to "Date", SortOrder.NAME to "Nom", SortOrder.SIZE to "Taille"),
            selected = s.sortOrder,
            onSelect = vm::setSort,
        )
        HorizontalDivider()

        SectionTitle("Dossiers autorisés")
        if (folders.isEmpty()) {
            Text("Aucun dossier autorisé.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        folders.forEach { (uri, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.removeFolder(uri) }) { Text("Retirer") }
            }
        }
        HorizontalDivider()

        SectionTitle("Cache")
        SwitchRow("Nettoyage automatique du cache", s.autoCleanCache, vm::setAutoClean)
        Text("Fichiers temporaires : ${formatSize(context, cacheSize)}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { vm.clearCache { freed -> message = "Cache vidé (${formatSize(context, freed)} libérés)." } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Vider le cache") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Text(
            "Le cache ne contient que des copies de travail ; vos APK et ZIP d'origine ne sont jamais modifiés ni supprimés.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()

        SectionTitle("À propos")
        Text("APK Installer · version ${vm.versionName}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Fonctionne hors ligne, sans publicité ni collecte de données. Android confirme chaque installation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> RadioGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
