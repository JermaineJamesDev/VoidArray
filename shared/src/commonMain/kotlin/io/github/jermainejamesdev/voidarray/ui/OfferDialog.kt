package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.IncomingOffer

@Composable
internal fun IncomingOfferDialog(
    offer: IncomingOffer,
    onRespond: (accept: Boolean, trust: Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    var trust by remember(offer.id) { mutableStateOf(false) }
    val canTrust = offer.verified && !offer.trusted

    AlertDialog(
        // Dismissing by tapping outside would leave the sender waiting until the prompt times out.
        onDismissRequest = {},
        icon = { IconBadge(deviceIcon(offer.sender.deviceType), size = 48.dp) },
        title = {
            Text(
                if (offer.isTextOnly) "Message from ${offer.sender.alias}" else "${offer.sender.alias} wants to send you files",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    offer.identityChanged -> Banner(
                        "This device's security key changed since you trusted it. Only accept if you reinstalled VoidArray on it.",
                        BannerKind.ERROR,
                    )
                    !offer.verified -> Banner(
                        "This device could not prove its identity. Only accept if you expected this.",
                        BannerKind.WARNING,
                    )
                    offer.trusted -> Banner("Trusted device", BannerKind.INFO)
                    else -> Banner(
                        "New device. Check that ${offer.sender.alias} shows the code ${offer.pairingCode}.",
                        BannerKind.INFO,
                    )
                }

                if (offer.files.isNotEmpty()) {
                    Text(
                        "${offer.files.size} file(s), ${formatBytes(offer.totalBytes)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        offer.files.forEach { file ->
                            Text(
                                "${file.name}  (${formatBytes(file.size)})",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                offer.text?.let { text -> MessageBox(text, onCopy = { onCopy(text) }) }

                if (canTrust) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = trust, onCheckedChange = { trust = it })
                        Text("Trust this device", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRespond(true, trust && canTrust) }) {
                Text(if (offer.isTextOnly) "Done" else "Accept")
            }
        },
        dismissButton = { TextButton(onClick = { onRespond(false, false) }) { Text("Decline") } },
    )
}
