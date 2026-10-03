package com.yunjam.eztransfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunjam.eztransfer.core.HistoryEntry
import com.yunjam.eztransfer.core.TransferDirection
import com.yunjam.eztransfer.core.TransferStatus

@Composable
internal fun HistoryHeader(hasEntries: Boolean, onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (hasEntries) TextButton(onClick = { confirming = true }) { Text("Clear") }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear history?") },
            text = { Text("This removes the list of past transfers. Received files are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun HistoryRow(entry: HistoryEntry, onCopy: (String) -> Unit, onOpenFolder: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val received = entry.direction == TransferDirection.RECEIVE
    val succeeded = entry.status == TransferStatus.COMPLETED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(
                    icon = when {
                        !succeeded -> AppIcons.Warning
                        received -> AppIcons.Download
                        else -> AppIcons.Upload
                    },
                    container = if (succeeded) scheme.secondaryContainer else scheme.errorContainer,
                    content = if (succeeded) scheme.onSecondaryContainer else scheme.onErrorContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    val what = when {
                        entry.fileCount == 0 -> "Message"
                        entry.fileCount == 1 -> entry.files.firstOrNull()?.name ?: "1 file"
                        else -> "${entry.fileCount} files"
                    }
                    Text(
                        "${if (received) "From" else "To"} ${entry.peerAlias}: $what",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        entry.finishedAtLabel,
                        entry.totalBytes.takeIf { it > 0 }?.let(::formatBytes),
                        if (succeeded) null else (entry.message ?: entry.status.name.lowercase()),
                    ).joinToString("  ·  ")
                    Text(details, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
                }
            }
            if (entry.fileCount > 1) {
                val names = entry.files.joinToString(", ") { it.name }
                val more = entry.fileCount - entry.files.size
                Text(
                    if (more > 0) "$names and $more more" else names,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.text?.let { text -> MessageBox(text, onCopy = { onCopy(text) }) }
            if (received && succeeded && entry.fileCount > 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenFolder) { Text("Open folder") }
                }
            }
        }
    }
}
