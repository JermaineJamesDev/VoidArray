package com.yunjam.eztransfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunjam.eztransfer.core.TransferDirection
import com.yunjam.eztransfer.core.TransferState
import com.yunjam.eztransfer.core.TransferStatus

@Composable
internal fun TransferCard(
    transfer: TransferState,
    onCancel: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val sending = transfer.direction == TransferDirection.SEND
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (icon, container, content) = when (transfer.status) {
                    TransferStatus.COMPLETED -> Triple(AppIcons.CheckCircle, scheme.primaryContainer, scheme.onPrimaryContainer)
                    TransferStatus.FAILED, TransferStatus.DECLINED, TransferStatus.CANCELLED ->
                        Triple(AppIcons.Warning, scheme.errorContainer, scheme.onErrorContainer)
                    else -> Triple(if (sending) AppIcons.Upload else AppIcons.Download, scheme.secondaryContainer, scheme.onSecondaryContainer)
                }
                IconBadge(icon, container = container, content = content)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title(transfer), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusLine(transfer),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (transfer.status == TransferStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (transfer.isActive) {
                    IconButton(onClick = onCancel) { Icon(AppIcons.Close, contentDescription = "Cancel transfer") }
                }
            }

            transfer.pairingCode?.let { code ->
                Banner(
                    message = "Check that ${transfer.peerAlias} shows the code $code before it accepts.",
                    kind = BannerKind.INFO,
                )
            }

            if (transfer.status == TransferStatus.IN_PROGRESS && transfer.totalBytes > 0) {
                LinearProgressIndicator(progress = { transfer.progress }, modifier = Modifier.fillMaxWidth())
                val details = listOfNotNull(
                    "${formatBytes(transfer.bytesTransferred)} of ${formatBytes(transfer.totalBytes)}",
                    transfer.bytesPerSecond.takeIf { it > 0 }?.let(::formatSpeed),
                    formatRemaining(transfer.totalBytes - transfer.bytesTransferred, transfer.bytesPerSecond),
                ).joinToString("  ·  ")
                Text(details, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }

            transfer.text?.let { text -> MessageBox(text, onCopy = { onCopyText(text) }) }
        }
    }
}

/** A received or sent message with copy and, for links, open actions. */
@Composable
internal fun MessageBox(text: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (looksLikeUrl(text)) {
                    TextButton(onClick = { runCatching { uriHandler.openUri(text.trim()) } }) { Text("Open link") }
                }
                TextButton(onClick = onCopy) { Text("Copy") }
            }
        }
    }
}

private fun title(transfer: TransferState): String {
    val direction = if (transfer.direction == TransferDirection.SEND) "To" else "From"
    val what = when {
        transfer.files.isEmpty() -> "message"
        transfer.files.size == 1 -> transfer.files.first().name
        else -> "${transfer.files.size} files"
    }
    return "$direction ${transfer.peerAlias}: $what"
}

private fun statusLine(transfer: TransferState): String = when (transfer.status) {
    TransferStatus.CONNECTING -> "Connecting securely"
    TransferStatus.WAITING_FOR_ACCEPT -> "Waiting for ${transfer.peerAlias} to accept"
    TransferStatus.IN_PROGRESS -> transfer.currentFile ?: "Transferring"
    TransferStatus.COMPLETED -> transfer.message ?: "Done, ${formatBytes(transfer.totalBytes)}"
    TransferStatus.DECLINED -> transfer.message ?: "Declined"
    TransferStatus.CANCELLED -> transfer.message ?: "Cancelled"
    TransferStatus.FAILED -> transfer.message ?: "Failed"
}
