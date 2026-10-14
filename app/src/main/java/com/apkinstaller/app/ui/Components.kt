package com.apkinstaller.app.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkinstaller.app.data.FileKind
import com.apkinstaller.app.data.ScannedFile
import java.text.DateFormat
import java.util.Date

fun formatSize(context: Context, bytes: Long): String =
    if (bytes < 0) "taille inconnue" else Formatter.formatShortFileSize(context, bytes)

fun formatDate(ms: Long): String =
    if (ms <= 0) "date inconnue" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Pastille représentant le format du fichier. */
@Composable
fun FormatBadge(kind: FileKind, modifier: Modifier = Modifier) {
    val (text, color) = when (kind) {
        FileKind.APK -> "APK" to Color(0xFF2E9E7F)
        FileKind.ARCHIVE -> "ZIP" to Color(0xFFC98A2B)
    }
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun FileRow(file: ScannedFile, onSelect: () -> Unit) {
    val context = LocalContext.current
    val details = remember(file) {
        "${if (file.kind == FileKind.APK) "APK" else "Archive ZIP"} · ${formatSize(context, file.size)} · ${formatDate(file.modifiedMs)}"
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormatBadge(file.kind)
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Choisir", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
