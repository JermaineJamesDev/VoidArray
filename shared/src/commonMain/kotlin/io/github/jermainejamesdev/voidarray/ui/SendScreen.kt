package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
        subtitle = if (staged.isEmpty()) null else "${staged.size} file(s), ${formatBytes(staged.sumOf { it.size })}",
        action = { if (hasContent) TextButton(onClick = onClear) { Text("Clear") } },
    ) {
        if (staged.isEmpty()) {
            EmptyState(
                icon = AppIcons.File,
                title = "No files selected",
                body = if (supportsDragAndDrop) "Add files, or drop them onto this window." else "Add files, or share them to VoidArray from another app.",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                staged.forEachIndexed { index, file ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(AppIcons.File, size = 36.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatBytes(file.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemove(index) }) { Icon(AppIcons.Close, contentDescription = "Remove ${file.name}") }
                    }
                }
            }
        }
        FilledTonalButton(onClick = onAddFiles) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add files")
        }
        OutlinedTextField(
            value = stagedText,
            onValueChange = onTextChange,
            label = { Text("Message or link (optional)") },
            leadingIcon = { Icon(AppIcons.Message, contentDescription = null) },
            minLines = 1,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
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
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_PORT.toString()) }
    var showManual by rememberSaveable { mutableStateOf(false) }

    SectionCard(
        title = "Send to",
        subtitle = if (canSend) "Tap a device to send" else "Choose files or type a message first",
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    FormationArray(modifier = Modifier.size(24.dp), periodMillis = 3_000)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onRescan, enabled = enabled && !scanning) {
                    Icon(AppIcons.Refresh, contentDescription = "Scan for devices")
                }
            }
        },
    ) {
        if (peers.isEmpty()) {
            EmptyState(
                icon = AppIcons.Computer,
                title = if (enabled) "Forming the array..." else "The array is closed",
                body = "Searching this network for other VoidArray devices. Open VoidArray on the other device, or add it by IP address.",
                art = { FormationArray(modifier = Modifier.size(72.dp), spinning = enabled, periodMillis = 8_000) },
            )
        }
        peers.forEach { peer -> PeerRow(peer, enabled = canSend && !peer.identityChanged, onClick = { onSend(peer) }) }

        if (showManual) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("IP address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(100.dp),
                )
            }
            val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (portNumber != null) {
                            onAddManual(host, portNumber)
                            host = ""
                            showManual = false
                        }
                    },
                    enabled = enabled && host.isNotBlank() && portNumber != null,
                ) { Text("Add device") }
                TextButton(onClick = { showManual = false }) { Text("Cancel") }
            }
        } else {
            OutlinedButton(onClick = { showManual = true }, enabled = enabled) { Text("Add by IP address") }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        border = if (peer.trusted) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f)) else null,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(deviceIcon(peer.info.deviceType), container = scheme.primaryContainer, content = scheme.onPrimaryContainer, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(peer.info.alias, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (peer.trusted) {
                        Icon(AppIcons.Lock, contentDescription = "Trusted", tint = scheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                val detail = when {
                    peer.identityChanged -> "Security key changed. Remove it from trusted devices in Settings if expected."
                    else -> listOfNotNull(
                        peer.addresses.firstOrNull(),
                        if (peer.source == PeerSource.MANUAL) "added by IP" else null,
                    ).joinToString("  ·  ")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peer.identityChanged) scheme.error else scheme.onSurfaceVariant,
                )
            }
            Icon(
                AppIcons.Send,
                contentDescription = "Send to ${peer.info.alias}",
                tint = if (enabled) scheme.primary else scheme.outline,
            )
        }
    }
}
