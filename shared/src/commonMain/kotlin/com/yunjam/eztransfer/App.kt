package com.yunjam.eztransfer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.yunjam.eztransfer.core.FileSummary
import com.yunjam.eztransfer.core.IncomingOffer
import com.yunjam.eztransfer.core.LocalStatus
import com.yunjam.eztransfer.core.Peer
import com.yunjam.eztransfer.core.PeerSource
import com.yunjam.eztransfer.core.PlatformActions
import com.yunjam.eztransfer.core.TransferController
import com.yunjam.eztransfer.core.TransferDirection
import com.yunjam.eztransfer.core.TransferState
import com.yunjam.eztransfer.core.TransferStatus
import com.yunjam.eztransfer.protocol.DEFAULT_PORT
import com.yunjam.eztransfer.protocol.DeviceType

@Composable
fun App(controller: TransferController, actions: PlatformActions) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val local by controller.local.collectAsState()
            val peers by controller.peers.collectAsState()
            val staged by controller.staged.collectAsState()
            val transfers by controller.transfers.collectAsState()
            val offer by controller.incomingOffer.collectAsState()

            Box(modifier = Modifier.fillMaxSize().safeContentPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { DeviceHeader(local, onRetry = actions::startNetworking) }
                    local.notice?.let { notice ->
                        item { NoticeCard(notice, onDismiss = controller::dismissNotice) }
                    }
                    item {
                        StagedFilesCard(
                            staged = staged,
                            onAdd = actions::pickFilesToSend,
                            onRemove = controller::removeStaged,
                            onClear = controller::clearStaged,
                        )
                    }
                    item {
                        PeersCard(
                            peers = peers,
                            canSend = staged.isNotEmpty() && local.serverRunning,
                            scanning = local.scanning,
                            enabled = local.serverRunning,
                            onSend = controller::sendStaged,
                            onRescan = controller::rescan,
                            onAddManual = controller::addManualPeer,
                        )
                    }
                    if (transfers.isNotEmpty()) {
                        item {
                            TransfersCard(
                                transfers = transfers,
                                onCancel = controller::cancelTransfer,
                                onClearFinished = controller::clearFinishedTransfers,
                            )
                        }
                    }
                    item { ReceiveCard(local.destinationLabel, onChange = actions::pickDestinationFolder) }
                }
            }

            offer?.let {
                IncomingOfferDialog(
                    offer = it,
                    onAccept = { controller.respondToOffer(true) },
                    onDecline = { controller.respondToOffer(false) },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun DeviceHeader(local: LocalStatus, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("EzTransfer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("This device: ${local.alias}", style = MaterialTheme.typography.bodyMedium)
        if (local.serverRunning) {
            val endpoints = local.addresses.joinToString(", ") { "$it:${local.port}" }
            Text(
                text = if (endpoints.isEmpty()) "No network connection" else "Reachable at $endpoints",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        local.problem?.let { problem ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(problem, style = MaterialTheme.typography.bodyMedium)
                    if (!local.serverRunning) {
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun StagedFilesCard(
    staged: List<FileSummary>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "Files to send",
        action = {
            Row {
                if (staged.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
                Button(onClick = onAdd) { Text("Add files") }
            }
        },
    ) {
        if (staged.isEmpty()) {
            Text(
                "Add files, then pick a device below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${staged.size} file(s), ${formatBytes(staged.sumOf { it.size })}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            staged.forEachIndexed { index, file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatBytes(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRemove(index) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun PeersCard(
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

    SectionCard(
        title = "Nearby devices",
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onRescan, enabled = enabled && !scanning) { Text("Scan") }
            }
        },
    ) {
        if (peers.isEmpty()) {
            Text(
                "Looking for devices on this network. Open EzTransfer on the other device, or add it by IP.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        peers.forEach { peer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.info.alias, fontWeight = FontWeight.Medium)
                    val kind = if (peer.info.deviceType == DeviceType.MOBILE) "Phone" else "Computer"
                    val via = if (peer.source == PeerSource.MANUAL) " (added by IP)" else ""
                    Text(
                        "$kind, ${peer.addresses.firstOrNull().orEmpty()}$via",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { onSend(peer) }, enabled = canSend) { Text("Send") }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
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
                modifier = Modifier.width(96.dp),
            )
        }
        val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
        OutlinedButton(
            onClick = { if (portNumber != null) onAddManual(host, portNumber) },
            enabled = enabled && host.isNotBlank() && portNumber != null,
        ) { Text("Add device") }
    }
}

@Composable
private fun TransfersCard(
    transfers: List<TransferState>,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    SectionCard(
        title = "Transfers",
        action = {
            if (transfers.any { !it.isActive }) TextButton(onClick = onClearFinished) { Text("Clear finished") }
        },
    ) {
        transfers.forEach { transfer ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val arrow = if (transfer.direction == TransferDirection.SEND) "To" else "From"
                    Column(modifier = Modifier.weight(1f)) {
                        Text("$arrow ${transfer.peerAlias}: ${transfer.files.size} file(s)", fontWeight = FontWeight.Medium)
                        Text(
                            statusText(transfer),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (transfer.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (transfer.isActive) TextButton(onClick = { onCancel(transfer.id) }) { Text("Cancel") }
                }
                if (transfer.isActive || transfer.status == TransferStatus.COMPLETED) {
                    LinearProgressIndicator(progress = { transfer.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun statusText(transfer: TransferState): String {
    val bytes = "${formatBytes(transfer.bytesTransferred)} of ${formatBytes(transfer.totalBytes)}"
    return when (transfer.status) {
        TransferStatus.CONNECTING -> "Connecting"
        TransferStatus.WAITING_FOR_ACCEPT -> "Waiting for the other device to accept"
        TransferStatus.IN_PROGRESS -> transfer.currentFile?.let { "$bytes: $it" } ?: bytes
        TransferStatus.COMPLETED -> transfer.message ?: "Done, ${formatBytes(transfer.totalBytes)}"
        TransferStatus.DECLINED -> transfer.message ?: "Declined"
        TransferStatus.CANCELLED -> transfer.message ?: "Cancelled"
        TransferStatus.FAILED -> transfer.message ?: "Failed"
    }
}

@Composable
private fun ReceiveCard(destinationLabel: String, onChange: () -> Unit) {
    SectionCard(
        title = "Receiving",
        action = { OutlinedButton(onClick = onChange) { Text("Change folder") } },
    ) {
        Text(
            "Files you accept are saved to:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(destinationLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IncomingOfferDialog(offer: IncomingOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        // Dismissing by tapping outside would leave the sender waiting until the prompt times out.
        onDismissRequest = {},
        title = { Text("Receive from ${offer.sender.alias}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${offer.files.size} file(s), ${formatBytes(offer.totalBytes)} from ${offer.senderAddress}")
                Column(
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    offer.files.forEach { file ->
                        Text(
                            "${file.name} (${formatBytes(file.size)})",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val tenths = (value * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}
