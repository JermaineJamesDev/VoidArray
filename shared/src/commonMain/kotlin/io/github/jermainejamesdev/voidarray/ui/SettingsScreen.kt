package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.ThemeMode
import io.github.jermainejamesdev.voidarray.core.TrustedDevice
import io.github.jermainejamesdev.voidarray.core.UserSettings

@Composable
internal fun DeviceSettingsCard(settings: UserSettings, onAliasChange: (String) -> Unit) {
    var alias by rememberSaveable { mutableStateOf(settings.alias) }
    // Follow external changes (e.g. the saved value after trimming) without clobbering edits in progress.
    LaunchedEffect(settings.alias) { alias = settings.alias }
    val changed = alias.isNotBlank() && alias.trim() != settings.alias

    SectionCard(title = "This device", subtitle = "The name other devices see") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(40) },
                label = { Text("Device name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (changed) onAliasChange(alias) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onAliasChange(alias) }, enabled = changed) { Text("Save") }
        }
    }
}

@Composable
internal fun ReceivingSettingsCard(
    settings: UserSettings,
    destinationLabel: String,
    onChangeFolder: () -> Unit,
    onAutoAcceptChange: (Boolean) -> Unit,
) {
    SectionCard(title = "Receiving") {
        SettingRow(title = "Save to", body = destinationLabel) {
            OutlinedButton(onClick = onChangeFolder) { Text("Change") }
        }
        InkDivider()
        SettingRow(
            title = "Auto-accept from trusted devices",
            body = "Skip the prompt for devices you have trusted. Other devices still ask.",
        ) {
            Switch(checked = settings.autoAcceptTrusted, onCheckedChange = onAutoAcceptChange)
        }
    }
}

@Composable
internal fun AppearanceSettingsCard(
    settings: UserSettings,
    supportsTray: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onTrayChange: (Boolean) -> Unit,
) {
    SectionCard(title = "Appearance and behavior") {
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.theme == mode,
                    onClick = { onThemeChange(mode) },
                    label = {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                        )
                    },
                )
            }
        }
        if (supportsTray) {
            InkDivider()
            SettingRow(
                title = "Keep running in the tray when closed",
                body = "Closing the window keeps this PC visible to your other devices.",
            ) {
                Switch(checked = settings.minimizeToTray, onCheckedChange = onTrayChange)
            }
        }
    }
}

@Composable
internal fun SecuritySettingsCard(
    fingerprint: String,
    trusted: List<TrustedDevice>,
    onForget: (TrustedDevice) -> Unit,
) {
    var forgetting by remember { mutableStateOf<TrustedDevice?>(null) }
    SectionCard(
        title = "Security",
        subtitle = "Transfers are encrypted. Devices are identified by their key.",
    ) {
        Text("This device's key", style = MaterialTheme.typography.bodyLarge)
        Text(
            fingerprint,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
        Text("Trusted devices", style = MaterialTheme.typography.bodyLarge)
        if (trusted.isEmpty()) {
            Text(
                "None yet. Tick \"Trust this device\" when accepting a transfer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trusted.forEach { device ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(AppIcons.Lock, size = 36.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.alias, fontWeight = FontWeight.Medium)
                    Text(
                        device.fingerprint.chunked(4).take(4).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { forgetting = device }) { Icon(AppIcons.Delete, contentDescription = "Forget ${device.alias}") }
            }
        }
    }
    forgetting?.let { device ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${device.alias}?") },
            text = { Text("Transfers from this device will ask for approval again.") },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    onForget(device)
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun AboutCard(version: String) {
    val uriHandler = LocalUriHandler.current
    SectionCard(title = "About VoidArray", subtitle = "Version $version") {
        Text(
            "Move files across the void between your own devices. Works entirely on your local network; nothing is sent to the internet. Open source under the Apache License 2.0.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(SOURCE_URL) } }) { Text("Source code") }
            TextButton(onClick = { runCatching { uriHandler.openUri("$SOURCE_URL/issues") } }) { Text("Report an issue") }
        }
    }
}

private const val SOURCE_URL = "https://github.com/JermaineJamesDev/VoidArray"

@Composable
private fun SettingRow(title: String, body: String, control: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}
