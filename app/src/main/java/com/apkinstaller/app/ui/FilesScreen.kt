package com.apkinstaller.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Document « Download » du stockage principal, utilisé comme position de départ des sélecteurs. */
val DownloadsInitialUri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

@Composable
fun FilesScreen(files: FilesViewModel, onSelect: (Uri) -> Unit) {
    val state by files.state.collectAsStateWithLifecycle()

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) files.onFolderPicked(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { files.refresh() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fichiers détectés (${state.files.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = files::refresh, enabled = !state.scanning) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Actualiser la recherche")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Accès aux dossiers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Android protège le stockage : l'application ne voit que les dossiers que vous autorisez. " +
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                "Depuis Android 11, le dossier Téléchargements lui-même ne peut pas être autorisé : " +
                                    "choisissez un de ses sous-dossiers, ou utilisez « Sélectionner un fichier » sur l'accueil."
                            } else {
                                "Choisissez le dossier Téléchargements (ou un autre) pour l'autoriser."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.needsStoragePermission) {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Autoriser la lecture du stockage") }
                    }
                    Button(onClick = { treeLauncher.launch(DownloadsInitialUri) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Autoriser l'accès aux Téléchargements")
                    }
                    OutlinedButton(onClick = { treeLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Ajouter un autre dossier")
                    }
                }
            }
        }
        items(state.notices) { n ->
            Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.files.isEmpty()) {
            item {
                Text(
                    when {
                        state.scanning -> "Recherche en cours…"
                        state.hasScanned -> "Aucun APK ou ZIP trouvé dans les dossiers accessibles."
                        else -> "La recherche n'a pas encore été lancée."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.files, key = { it.uri.toString() }) { f ->
            FileRow(f, onSelect = { onSelect(f.uri) })
        }
    }
}
