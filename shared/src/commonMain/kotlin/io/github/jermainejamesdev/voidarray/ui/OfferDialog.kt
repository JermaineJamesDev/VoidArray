package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jermainejamesdev.voidarray.core.IncomingOffer
import io.github.jermainejamesdev.voidarray.core.looksExecutable
import kotlinx.coroutines.delay

/**
 * An offer can arrive at any moment, including while the user is tapping elsewhere, so Accept only
 * becomes active after this long. A tap meant for something else then cannot accept files.
 */
private const val ACCEPT_ARMING_MILLIS = 1_000L

/**
 * The accept prompt. It cannot be dismissed by tapping outside or pressing back, which would leave the
 * sender waiting until the prompt times out; the user answers with Accept or Decline.
 */
@Composable
internal fun IncomingOfferDialog(
    offer: IncomingOffer,
    onRespond: (accept: Boolean, trust: Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = null) { asSheet, modifier ->
        OfferContent(offer, onRespond, onCopy, asSheet = asSheet, modifier = modifier)
    }
}

/**
 * A bottom sheet on phones and a centered panel on wide windows. [content] receives whether it is a sheet
 * and the modifier that keeps it clear of the navigation bar. With a null [onDismissRequest] only the
 * content's own buttons can close it.
 */
@Composable
internal fun AdaptiveSheet(
    onDismissRequest: (() -> Unit)?,
    content: @Composable (asSheet: Boolean, modifier: Modifier) -> Unit,
) {
    val dismissible = onDismissRequest != null
    Dialog(
        onDismissRequest = { onDismissRequest?.invoke() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            Surface(
                modifier = if (wide) {
                    Modifier.align(Alignment.Center).width(480.dp).heightIn(max = maxHeight - 48.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight - 24.dp)
                },
                shape = if (wide) MaterialTheme.shapes.extraLarge else CutCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                content(!wide, if (wide) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            }
        }
    }
}

/** The drag-handle bar at the top of a bottom sheet. */
@Composable
internal fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.width(36.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun OfferContent(
    offer: IncomingOffer,
    onRespond: (accept: Boolean, trust: Boolean) -> Unit,
    onCopy: (String) -> Unit,
    asSheet: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var trust by remember(offer.id) { mutableStateOf(false) }
    var armed by remember(offer.id) { mutableStateOf(false) }
    LaunchedEffect(offer.id) {
        delay(ACCEPT_ARMING_MILLIS)
        armed = true
    }
    val canTrust = !offer.trusted && !offer.identityChanged
    val programs = offer.files.filter { looksExecutable(it.name) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = if (asSheet) 12.dp else 24.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (asSheet) SheetHandle(Modifier.align(Alignment.CenterHorizontally))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (offer.identityChanged) {
                IconTile(deviceIcon(offer.sender.deviceType), tone = Tone.DANGER, size = 56.dp)
            } else {
                Box(contentAlignment = Alignment.Center) {
                    FormationArray(modifier = Modifier.size(60.dp), periodMillis = 16_000)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(deviceIcon(offer.sender.deviceType), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(17.dp))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(offer.sender.alias, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        offer.isTextOnly -> "sent you a message"
                        else -> "wants to send you ${countLabel(offer.files.size, "file")} · ${formatBytes(offer.totalBytes)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        when {
            offer.identityChanged -> Banner(
                title = "This device's key changed",
                message = "It doesn't match the key you trusted. Someone on your network may be posing as " +
                    "${offer.sender.alias}. Accept only if you reinstalled VoidArray on it.",
                kind = BannerKind.ERROR,
            )
            offer.trusted -> Banner(
                title = "Trusted device",
                message = "You trusted this device's key before, so there is no code to compare.",
                kind = BannerKind.INFO,
            )
            else -> PairingCodeToken(
                code = offer.pairingCode,
                caption = "Accept only if ${offer.sender.alias} shows this same code.",
                header = {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AppIcons.Shield, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                        Text("New device", style = MaterialTheme.typography.titleSmall)
                    }
                },
            )
        }

        if (offer.files.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupLabel("Files")
                Column(
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    offer.files.forEach { file ->
                        val program = looksExecutable(file.name)
                        InsetRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
                            Icon(
                                fileIcon(file.name),
                                contentDescription = null,
                                tint = if (program) VoidArrayTheme.extras.warning else scheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (program) StatusChip("Program", Tone.WARNING)
                            Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, fontFamily = VoidArrayTheme.extras.mono, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                if (programs.isNotEmpty()) {
                    Text(
                        "Program files can run code on this device when opened.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        offer.text?.let { text -> MessageBox(text, onCopy = { onCopy(text) }) }

        if (canTrust) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .toggleable(value = trust, role = Role.Checkbox, onValueChange = { trust = it }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Codes match. Trust this device", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tick only if ${offer.sender.alias} shows the same code. Its key is then remembered so later transfers can skip this prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // The row handles the toggle so the whole line is one touch target.
                Checkbox(checked = trust, onCheckedChange = null)
            }
        }

        if (offer.identityChanged) {
            // A changed key makes declining the emphasized, default-looking choice.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EmphaticButton("Decline", onClick = { onRespond(false, false) }, modifier = Modifier.fillMaxWidth().height(50.dp))
                QuietButton(
                    "Accept anyway",
                    onClick = { onRespond(true, false) },
                    enabled = armed,
                    color = scheme.error,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Decline", onClick = { onRespond(false, false) }, modifier = Modifier.weight(1f).height(50.dp))
                PrimaryButton(
                    if (offer.isTextOnly) "Done" else "Accept",
                    onClick = { onRespond(true, trust && canTrust) },
                    enabled = armed,
                    modifier = Modifier.weight(1f).height(50.dp),
                )
            }
        }
    }
}
