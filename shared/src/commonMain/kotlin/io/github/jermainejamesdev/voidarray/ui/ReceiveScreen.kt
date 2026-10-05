package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.LocalStatus
import io.github.jermainejamesdev.voidarray.protocol.DeviceType

@Composable
internal fun ReceiveStatusCard(
    local: LocalStatus,
    deviceType: DeviceType,
    discoverable: Boolean,
    onCopy: (String) -> Unit,
    onShowQr: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow, contentColor = scheme.onSurface),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    FormationArray(modifier = Modifier.size(104.dp), spinning = local.serverRunning, periodMillis = 40_000)
                    Box(
                        modifier = Modifier.size(38.dp).clip(CircleShape)
                            .background(if (local.serverRunning) scheme.primary else scheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            deviceIcon(deviceType),
                            contentDescription = null,
                            tint = if (local.serverRunning) scheme.onPrimary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(local.alias, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (local.serverRunning) scheme.primary else scheme.error))
                        Text(
                            if (local.serverRunning) "Ready to receive" else "Not receiving",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (local.serverRunning) scheme.primary else scheme.error,
                        )
                    }
                    Text(
                        when {
                            !local.serverRunning -> "Other devices cannot reach this one"
                            discoverable -> "Visible to devices on this network"
                            else -> "Hidden from nearby devices. Pair with a QR code or IP address."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (local.serverRunning) {
                    val endpoints = local.addresses.map { "$it:${local.port}" }
                    DetailRow(
                        label = "Address",
                        value = endpoints.joinToString("  ").ifEmpty { "No network connection" },
                        copyLabel = "Copy address",
                        onCopy = endpoints.takeIf { it.isNotEmpty() }?.let { { onCopy(it.first()) } },
                    )
                    SecondaryButton("Show pairing QR", onClick = onShowQr, icon = AppIcons.QrCode)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, copyLabel: String, onCopy: (() -> Unit)?) {
    InsetRow(contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = VoidArrayTheme.extras.mono,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(AppIcons.Copy, contentDescription = copyLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun ReceiveFolderCard(destinationLabel: String, onChange: () -> Unit, onOpen: () -> Unit) {
    SectionCard(title = "Save received files to") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(AppIcons.Folder, tone = Tone.GOLD)
            Text(destinationLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Change", onClick = onChange)
            QuietButton("Open folder", onClick = onOpen)
        }
    }
}
