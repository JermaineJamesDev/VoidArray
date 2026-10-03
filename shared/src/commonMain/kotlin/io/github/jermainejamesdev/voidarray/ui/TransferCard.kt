package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.TransferDirection
import io.github.jermainejamesdev.voidarray.core.TransferState
import io.github.jermainejamesdev.voidarray.core.TransferStatus

@Composable
internal fun TransferCard(
    transfer: TransferState,
    onCancel: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val mono = VoidArrayTheme.extras.mono
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        BoxWithConstraints {
            // Beside the title when there is room; on a phone it gets its own full-width token below.
            val codeInline = maxWidth >= 600.dp
            Column(
                modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (transfer.isActive) {
                        FormationArray(modifier = Modifier.size(40.dp), periodMillis = 6_000)
                    } else {
                        val (icon, tone) = when (transfer.status) {
                            TransferStatus.COMPLETED -> AppIcons.Check to Tone.JADE
                            TransferStatus.CANCELLED, TransferStatus.DECLINED -> AppIcons.Close to Tone.NEUTRAL
                            else -> AppIcons.Warning to Tone.DANGER
                        }
                        IconTile(icon, tone = tone)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title(transfer, scheme.onSurfaceVariant),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (transfer.status == TransferStatus.IN_PROGRESS && transfer.totalBytes > 0) {
                                Text("${(transfer.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontFamily = mono)
                            }
                        }
                        Text(
                            statusLine(transfer),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (transfer.status == TransferStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (codeInline) transfer.pairingCode?.let { CompactPairingCode(it) }
                    when {
                        transfer.isActive -> IconButton(onClick = onCancel) {
                            Icon(AppIcons.Close, contentDescription = "Cancel transfer", tint = scheme.onSurfaceVariant)
                        }
                        transfer.status == TransferStatus.COMPLETED -> SealStamp(modifier = Modifier.padding(end = 8.dp))
                    }
                }

                if (!codeInline) {
                    transfer.pairingCode?.let { code ->
                        PairingCodeToken(code = code, caption = "${transfer.peerAlias} should show this same code before accepting")
                    }
                }

                if (transfer.status == TransferStatus.IN_PROGRESS && transfer.totalBytes > 0) {
                    Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressTrack(transfer.progress)
                        val details = listOfNotNull(
                            transfer.currentFile,
                            "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.totalBytes)}",
                            transfer.bytesPerSecond.takeIf { it > 0 }?.let(::formatSpeed),
                            formatRemaining(transfer.totalBytes - transfer.bytesTransferred, transfer.bytesPerSecond),
                        ).joinToString("  ·  ")
                        Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = mono, color = scheme.onSurfaceVariant)
                    }
                }

                transfer.text?.let { text ->
                    MessageBox(text, onCopy = { onCopyText(text) }, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}

/** A plain rounded bar: thicker than Material's default so progress reads at a glance. */
@Composable
private fun ProgressTrack(progress: Float) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(3.dp)
    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(shape).background(scheme.surfaceContainerHigh)) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(shape).background(scheme.primary))
    }
}

/** A received or sent message with copy and, for links, open actions. */
@Composable
internal fun MessageBox(text: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (looksLikeUrl(text)) {
                QuietButton("Open link", onClick = { runCatching { uriHandler.openUri(text.trim()) } }, icon = AppIcons.Link)
            }
            QuietButton("Copy", onClick = onCopy, icon = AppIcons.Copy)
        }
    }
}

private fun title(transfer: TransferState, muted: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val direction = if (transfer.direction == TransferDirection.SEND) "To" else "From"
    val what = when {
        transfer.files.isEmpty() -> "message"
        transfer.files.size == 1 -> transfer.files.first().name
        else -> "${transfer.files.size} files"
    }
    append("$direction ${transfer.peerAlias}")
    withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Normal)) { append(" · $what") }
}

private fun statusLine(transfer: TransferState): String = when (transfer.status) {
    TransferStatus.CONNECTING -> "Connecting securely"
    TransferStatus.WAITING_FOR_ACCEPT -> "Waiting for ${transfer.peerAlias} to accept"
    TransferStatus.IN_PROGRESS -> if (transfer.direction == TransferDirection.SEND) "Sending" else "Receiving"
    TransferStatus.COMPLETED -> transfer.message ?: "Delivered · ${formatBytes(transfer.totalBytes)}"
    TransferStatus.DECLINED -> transfer.message ?: "Declined"
    TransferStatus.CANCELLED -> transfer.message ?: "Cancelled"
    TransferStatus.FAILED -> transfer.message ?: "Failed"
}
