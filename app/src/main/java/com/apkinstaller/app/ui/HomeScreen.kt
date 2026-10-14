package com.apkinstaller.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkinstaller.app.install.AppGroup
import com.apkinstaller.app.install.SignatureCheck
import com.apkinstaller.app.install.VersionComparison

@Composable
fun HomeScreen(
    install: InstallationViewModel,
    files: FilesViewModel,
    onPickFile: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onSeeAllFiles: () -> Unit,
) {
    val state by install.state.collectAsStateWithLifecycle()
    val filesState by files.state.collectAsStateWithLifecycle()
    val busy = state is InstallUiState.Working

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Sélectionner un fichier")
        }

        when (val s = state) {
            InstallUiState.Idle -> Text(
                "Choisissez un fichier APK ou une archive ZIP, ou ouvrez-en un depuis le gestionnaire de fichiers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is InstallUiState.Working -> WorkingCard(s.stage, onCancel = install::cancel)
            is InstallUiState.ChooseApp -> ChooseAppCard(s, onChoose = install::chooseGroup, onCancel = install::reset)
            is InstallUiState.Ready -> {
                AppInfoCard(s.plan)
                InstallActions(s, install, onOpenInstallSettings)
            }
            is InstallUiState.AwaitingConfirmation -> {
                AppInfoCard(s.plan)
                StatusCard(
                    "Confirmation Android nécessaire",
                    "Validez l'installation dans la fenêtre du système. Le résultat s'affichera ici une fois confirmé par Android.",
                )
                OutlinedButton(onClick = install::cancel, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
            }
            is InstallUiState.Success -> {
                AppInfoCard(s.plan)
                StatusCard("Installation terminée", "${s.plan.info.label} a été installée (confirmé par Android).")
                OutlinedButton(onClick = install::reset, modifier = Modifier.fillMaxWidth()) { Text("Terminer") }
            }
            is InstallUiState.Cancelled -> {
                s.plan?.let { AppInfoCard(it) }
                StatusCard("Installation annulée", "L'installation a été annulée.")
                CancelledFailedActions(s.plan != null, install)
            }
            is InstallUiState.Failed -> {
                s.plan?.let { AppInfoCard(it) }
                StatusCard("Installation échouée", s.message, error = true)
                CancelledFailedActions(s.plan != null, install)
            }
        }

        DetectedSection(filesState, files, install, onSeeAllFiles)
    }
}

@Composable
private fun CancelledFailedActions(canRetry: Boolean, install: InstallationViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canRetry) Button(onClick = install::backToReady, modifier = Modifier.weight(1f)) { Text("Réessayer") }
        OutlinedButton(onClick = install::reset, modifier = Modifier.weight(1f)) { Text("Fermer") }
    }
}

@Composable
private fun InstallActions(s: InstallUiState.Ready, install: InstallationViewModel, onOpenInstallSettings: () -> Unit) {
    val info = s.plan.info
    if (!s.installAllowed) {
        StatusCard(
            "Autorisation requise",
            "Android doit d'abord autoriser APK Installer à installer des applications. " +
                "Ouvrez le réglage, activez « Autoriser depuis cette source », puis revenez ici.",
            error = true,
        )
        Button(onClick = onOpenInstallSettings, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir le réglage Android") }
        return
    }
    val label = when (info.comparison) {
        VersionComparison.NOT_INSTALLED -> "Installer"
        VersionComparison.NEWER -> "Mettre à jour"
        VersionComparison.SAME -> "Réinstaller"
        VersionComparison.OLDER -> "Tenter l'installation"
    }
    Button(onClick = install::install, modifier = Modifier.fillMaxWidth(), enabled = !info.minSdkTooHigh) { Text(label) }
    OutlinedButton(onClick = install::reset, modifier = Modifier.fillMaxWidth()) { Text("Annuler la sélection") }
}

@Composable
private fun WorkingCard(stage: Stage, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stage.label, style = MaterialTheme.typography.titleMedium)
            // Progression indéterminée : on n'invente pas de pourcentage.
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

@Composable
private fun ChooseAppCard(s: InstallUiState.ChooseApp, onChoose: (AppGroup) -> Unit, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${s.groups.size} applications dans « ${s.sourceName} »", style = MaterialTheme.typography.titleMedium)
            Text("Choisissez celle à installer.", style = MaterialTheme.typography.bodyMedium)
            s.groups.forEach { g ->
                val v = g.base.manifest
                OutlinedButton(onClick = { onChoose(g) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(g.base.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${g.packageName} · v${v.versionName ?: v.versionCode}" +
                                if (g.isSplitApp) " · ${g.entries.size} APK (fractionné)" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            s.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

@Composable
private fun StatusCard(title: String, text: String, error: Boolean = false) {
    val colors = if (error) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AppInfoCard(plan: InstallPlan) {
    val context = LocalContext.current
    val info = plan.info
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                info.icon?.let {
                    Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(info.label, style = MaterialTheme.typography.titleLarge)
                    Text(info.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Version : ${info.versionName ?: "?"} (code ${info.versionCode})", style = MaterialTheme.typography.bodyMedium)
            Text("Taille : ${formatSize(context, plan.totalSize)} · Source : ${plan.sourceName}", style = MaterialTheme.typography.bodyMedium)
            if (plan.splitNames.isNotEmpty()) {
                Text("Splits : ${plan.splitNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }

            val installedText = when (info.comparison) {
                VersionComparison.NOT_INSTALLED -> "Non installée sur cet appareil (ou état non vérifiable)."
                VersionComparison.NEWER -> "Mise à jour : version installée ${info.installedVersionName ?: "?"} (code ${info.installedVersionCode})."
                VersionComparison.SAME -> "Même version déjà installée (code ${info.installedVersionCode})."
                VersionComparison.OLDER ->
                    "Attention : version plus ancienne que celle installée (${info.installedVersionName ?: "?"}, code ${info.installedVersionCode}). " +
                        "Android refuse normalement une rétrogradation ; le succès n'est pas garanti."
            }
            val warn = info.comparison == VersionComparison.OLDER
            Text(
                installedText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (info.signature == SignatureCheck.MISMATCH) {
                Text(
                    "Signature différente de l'application installée : Android refusera très probablement cette installation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (info.minSdkTooHigh) {
                Text(
                    "Cette application nécessite une version d'Android plus récente (API ${info.minSdk}) : installation impossible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            plan.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DetectedSection(state: FilesUiState, files: FilesViewModel, install: InstallationViewModel, onSeeAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Fichiers détectés", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = files::refresh, enabled = !state.scanning) {
            Icon(Icons.Filled.Refresh, contentDescription = "Actualiser la recherche")
        }
    }
    if (state.files.isEmpty()) {
        Text(
            if (state.scanning) "Recherche en cours…"
            else "Aucun fichier détecté automatiquement. Android limite l'accès à Téléchargements : autorisez un dossier depuis l'onglet Fichiers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        state.files.take(5).forEach { f -> FileRow(f, onSelect = { install.onFileSelected(f.uri) }) }
        if (state.files.size > 5) TextButton(onClick = onSeeAll) { Text("Voir les ${state.files.size} fichiers") }
    }
}
