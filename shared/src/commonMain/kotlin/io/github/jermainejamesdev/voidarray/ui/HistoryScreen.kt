package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.HistoryEntry
import io.github.jermainejamesdev.voidarray.core.TransferDirection
import io.github.jermainejamesdev.voidarray.core.TransferStatus

/** The Clear action for the History screen's title row, with its confirmation. */
@Composable
internal fun ClearHistoryAction(onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    QuietButton("Clear", onClick = { confirming = true })
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear history?") },
            text = { Text("This removes the list of past transfers. Received files are not deleted.") },
            confirmButton = {
                QuietButton("Clear", onClick = {
                    confirming = false
                    onClear()
                })
            },
            dismissButton = { QuietButton("Cancel", onClick = { confirming = false }) },
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(
                    icon = when {
                        !succeeded -> AppIcons.Warning
                        received -> AppIcons.Download
                        else -> AppIcons.Upload
                    },
                    tone = if (succeeded) Tone.JADE else Tone.DANGER,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val what = when {
                        entry.fileCount == 0 -> "message"
                        entry.fileCount == 1 -> entry.files.firstOrNull()?.name ?: "1 file"
                        else -> "${entry.fileCount} files"
                    }
                    Text(
                        buildAnnotatedString {
                            append("${if (received) "From" else "To"} ${entry.peerAlias}")
                            withStyle(SpanStyle(color = scheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(" · $what") }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        entry.finishedAtLabel,
                        entry.totalBytes.takeIf { it > 0 }?.let(::formatBytes),
                        if (succeeded) null else (entry.message ?: entry.status.name.lowercase()),
                    ).joinToString(" · ")
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (succeeded) scheme.onSurfaceVariant else scheme.error,
                        maxLines = 2,
                    )
                }
                if (succeeded) SealStamp(modifier = Modifier.padding(end = 4.dp))
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
                    QuietButton("Open folder", onClick = onOpenFolder, icon = AppIcons.Folder)
                }
            }
        }
    }
}
