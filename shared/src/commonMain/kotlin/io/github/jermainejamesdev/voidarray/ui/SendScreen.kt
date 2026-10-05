package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.FileSummary
import io.github.jermainejamesdev.voidarray.core.Peer
import io.github.jermainejamesdev.voidarray.core.PeerSource
import io.github.jermainejamesdev.voidarray.protocol.DEFAULT_PORT

@Composable
internal fun SendContentCard(
    staged: List<FileSummary>,
    stagedText: String,
    supportsDragAndDrop: Boolean,
    onAddFiles: () -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    val hasContent = staged.isNotEmpty() || stagedText.isNotBlank()
    SectionCard(
        title = "What to send",
        subtitle = if (staged.isEmpty()) "Files, a message, or both" else "${countLabel(staged.size, "file")} · ${formatBytes(staged.sumOf { it.size })}",
        action = { if (hasContent) QuietButton("Clear", onClick = onClear) },
    ) {
        if (staged.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                staged.forEachIndexed { index, file ->
                    InsetRow {
                        IconTile(fileIcon(file.name), tone = Tone.GOLD, size = 36.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatBytes(file.size),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = VoidArrayTheme.extras.mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(AppIcons.Close, contentDescription = "Remove ${file.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (supportsDragAndDrop) {
            Row(
                modifier = Modifier.fillMaxWidth().dashedOutline(MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FormationArray(modifier = Modifier.size(28.dp), spinning = false)
                Text(
                    if (staged.isEmpty()) "Drop files anywhere on this window" else "Drop more files here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton("Add files", onClick = onAddFiles, icon = AppIcons.Add)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SecondaryButton("Add files", onClick = onAddFiles, icon = AppIcons.Add)
                if (staged.isEmpty()) {
                    Text(
                        "Or share files to VoidArray from another app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Message or link", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = stagedText,
                onValueChange = onTextChange,
                placeholder = { Text("Optional: a note, or a link to open on the other device") },
                minLines = 1,
                maxLines = 4,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun DevicesCard(
    peers: List<Peer>,
    canSend: Boolean,
    scanning: Boolean,
    enabled: Boolean,
    onSend: (Peer) -> Unit,
    onRescan: () -> Unit,
    onAddManual: (String, Int) -> Unit,
    onReview: () -> Unit,
    onShowQr: () -> Unit,
    /** Null where there is no camera to scan with. */
    onScanQr: (() -> Unit)?,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_PORT.toString()) }
    var showManual by rememberSaveable { mutableStateOf(false) }

    SectionCard(
        title = "Nearby devices",
        subtitle = when {
            peers.isEmpty() -> null
            canSend -> "${countLabel(peers.size, "device")} on this network"
            else -> "Choose files or type a message first"
        },
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) FormationArray(modifier = Modifier.size(22.dp).padding(end = 2.dp), periodMillis = 3_000)
                SecondaryButton("Scan", onClick = onRescan, enabled = enabled && !scanning, icon = AppIcons.Refresh)
            }
        },
    ) {
        if (peers.isEmpty()) {
            EmptyState(
                icon = AppIcons.Computer,
                title = if (enabled) "Forming the array" else "Not receiving",
                body = "Looking for other VoidArray devices on this network. Open VoidArray on the other device, or pair with a QR code or IP address.",
                art = { FormationArray(modifier = Modifier.size(64.dp), spinning = enabled, periodMillis = 8_000) },
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                peers.forEach { peer -> PeerRow(peer, canSend = canSend, onSend = { onSend(peer) }, onReview = onReview) }
            }
        }

        if (showManual) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("IP address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.small,
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                    colors = fieldColors(),
                    modifier = Modifier.width(100.dp),
                )
            }
            val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "Add device",
                    onClick = {
                        if (portNumber != null) {
                            onAddManual(host, portNumber)
                            host = ""
                            showManual = false
                        }
                    },
                    enabled = enabled && host.isNotBlank() && portNumber != null,
                )
                QuietButton("Cancel", onClick = { showManual = false })
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onScanQr != null) SecondaryButton("Scan QR code", onClick = onScanQr, enabled = enabled, icon = AppIcons.QrScanner)
                QuietButton("Show pairing QR", onClick = onShowQr, enabled = enabled, icon = AppIcons.QrCode)
                QuietButton("Add by IP address", onClick = { showManual = true }, enabled = enabled, icon = AppIcons.Add)
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, canSend: Boolean, onSend: () -> Unit, onReview: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val chip = @Composable {
        when {
            peer.identityChanged -> StatusChip("Key changed", Tone.DANGER, icon = AppIcons.Warning)
            peer.trusted -> StatusChip("Trusted", Tone.JADE, icon = AppIcons.Lock)
            else -> StatusChip("New", Tone.NEUTRAL)
        }
    }
    BoxWithConstraints {
        // On a phone the name needs the whole line, so the status chip drops to the detail line.
        val compact = maxWidth < 440.dp
        InsetRow(contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
            IconTile(deviceIcon(peer.info.deviceType), tone = if (peer.identityChanged) Tone.DANGER else Tone.JADE, size = 42.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        peer.info.alias,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!compact) chip()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (compact) chip()
                    if (peer.identityChanged) {
                        Text("Sending is paused. Review it in Settings.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        Text(
                            listOfNotNull(peer.addresses.firstOrNull(), if (peer.source == PeerSource.MANUAL) "added by IP" else null)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = VoidArrayTheme.extras.mono,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (peer.identityChanged) {
                SecondaryButton("Review", onClick = onReview)
            } else {
                PrimaryButton("Send", onClick = onSend, enabled = canSend, icon = if (compact) null else AppIcons.Send)
            }
        }
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

internal fun countLabel(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** A dashed rectangle marking a drop target; Compose borders cannot be dashed. */
private fun Modifier.dashedOutline(color: Color): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}
