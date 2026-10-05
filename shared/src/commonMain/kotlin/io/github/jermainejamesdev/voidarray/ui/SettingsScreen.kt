package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.ThemeMode
import io.github.jermainejamesdev.voidarray.core.TrustedDevice
import io.github.jermainejamesdev.voidarray.core.UserSettings

@Composable
internal fun DeviceSettingsCard(
    settings: UserSettings,
    onAliasChange: (String) -> Unit,
    onDiscoverableChange: (Boolean) -> Unit,
) {
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
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            PrimaryButton("Save", onClick = { onAliasChange(alias) }, enabled = changed)
        }
        RowDivider()
        SettingRow(
            title = "Visible to nearby devices",
            body = "Lets other devices on this network find this one by name. When off, pair with a QR code or IP address; " +
                "devices that already know this one can still send to it.",
        ) {
            Switch(checked = settings.discoverable, onCheckedChange = onDiscoverableChange)
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
            SecondaryButton("Change", onClick = onChangeFolder)
        }
        RowDivider()
        SettingRow(
            title = "Auto-accept from trusted devices",
            body = "Skip the prompt for devices you have trusted. Programs, and files from other devices, still ask.",
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
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
        if (supportsTray) {
            RowDivider()
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
    onCopyKey: (String) -> Unit,
    onResetKey: () -> Unit,
) {
    var forgetting by remember { mutableStateOf<TrustedDevice?>(null) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val mono = VoidArrayTheme.extras.mono
    SectionCard(
        title = "Security",
        subtitle = "Transfers are encrypted. Devices are identified by their key.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("This device's key", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Identifies this device to others. It is not a secret.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QuietButton(if (showKey) "Hide details" else "Show details", onClick = { showKey = !showKey })
            }
            if (showKey) {
                InsetRow {
                    Text(
                        // Two lines of eight groups read more easily than one wrapped run.
                        fingerprint.split(' ').chunked(8).joinToString("\n") { it.joinToString(" ") },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onCopyKey(fingerprint) }) {
                        Icon(AppIcons.Copy, contentDescription = "Copy key fingerprint", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "This is a fingerprint of the public half of the key; the private half never leaves this device. " +
                        "Other devices list it by its first 16 characters under Trusted devices. If those match, they " +
                        "paired with this device and not an impostor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuietButton("Reset key", onClick = { confirmReset = true }, color = MaterialTheme.colorScheme.error)
            }
        }
        RowDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupLabel("Trusted devices")
            if (trusted.isEmpty()) {
                Text(
                    "None yet. Pair with a QR code, or tick \"Codes match\" when accepting a transfer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trusted.forEach { device ->
                InsetRow {
                    IconTile(AppIcons.Lock, tone = Tone.JADE, size = 36.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.alias, style = MaterialTheme.typography.titleSmall)
                        Text(
                            device.fingerprint.chunked(4).take(4).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { forgetting = device }) {
                        Icon(AppIcons.Delete, contentDescription = "Forget ${device.alias}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    forgetting?.let { device ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${device.alias}?") },
            text = { Text("Transfers from this device will ask for approval again.") },
            confirmButton = {
                QuietButton("Forget", onClick = {
                    forgetting = null
                    onForget(device)
                }, color = MaterialTheme.colorScheme.error)
            },
            dismissButton = { QuietButton("Cancel", onClick = { forgetting = null }) },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset this device's key?") },
            text = {
                Text(
                    "VoidArray creates a new key for this device. Devices that trusted it will show \"Key changed\" and " +
                        "stop sending to it until you pair them again. Do this if the key may have been copied, for " +
                        "example from a lost USB drive with the portable app.",
                )
            },
            confirmButton = {
                QuietButton("Reset key", onClick = {
                    confirmReset = false
                    onResetKey()
                }, color = MaterialTheme.colorScheme.error)
            },
            dismissButton = { QuietButton("Cancel", onClick = { confirmReset = false }) },
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
            SecondaryButton("Source code", onClick = { runCatching { uriHandler.openUri(SOURCE_URL) } })
            QuietButton("Report an issue", onClick = { runCatching { uriHandler.openUri("$SOURCE_URL/issues") } })
        }
    }
}

private const val SOURCE_URL = "https://github.com/JermaineJamesDev/VoidArray"

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
