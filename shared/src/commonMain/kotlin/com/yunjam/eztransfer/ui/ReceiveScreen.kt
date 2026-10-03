package com.yunjam.eztransfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunjam.eztransfer.core.LocalStatus
import com.yunjam.eztransfer.protocol.DeviceType

@Composable
internal fun ReceiveStatusCard(local: LocalStatus, deviceType: DeviceType) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBadge(deviceIcon(deviceType), size = 72.dp, container = scheme.primary, content = scheme.onPrimary)
            Text(local.alias, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (local.serverRunning) "Ready to receive" else "Not receiving",
                style = MaterialTheme.typography.titleSmall,
            )
            if (local.serverRunning) {
                val endpoints = local.addresses.joinToString("   ") { "$it:${local.port}" }
                Text(
                    if (endpoints.isEmpty()) "No network connection" else endpoints,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Key ${local.fingerprint.split(' ').take(4).joinToString(" ")}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun ReceiveFolderCard(destinationLabel: String, onChange: () -> Unit, onOpen: () -> Unit) {
    SectionCard(title = "Save received files to") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(AppIcons.Folder)
            Text(destinationLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChange) { Text("Change") }
            TextButton(onClick = onOpen) { Text("Open folder") }
        }
    }
}
