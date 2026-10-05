package io.github.jermainejamesdev.voidarray.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jermainejamesdev.voidarray.core.PairingInvite
import io.github.jermainejamesdev.voidarray.core.PairingRequest
import io.github.jermainejamesdev.voidarray.core.QrMatrix
import io.github.jermainejamesdev.voidarray.core.QrPairingState
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Same arming delay as the offer prompt, so a stray tap cannot approve a pairing. */
private const val PAIR_ARMING_MILLIS = 1_000L

/**
 * This device's pairing QR code, then, once another device has scanned it, the prompt to approve that
 * device. [fallbackEndpoint] is offered for typing in when the other device cannot scan.
 */
@Composable
internal fun PairingQrDialog(
    invite: PairingInvite,
    fallbackEndpoint: String?,
    onRespond: (accept: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val request = invite.request
    // While a device waits for an answer, only Pair or Decline may close the prompt.
    AdaptiveSheet(onDismissRequest = if (request == null) onClose else null) { asSheet, modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = if (asSheet) 12.dp else 24.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (asSheet) SheetHandle(Modifier.align(Alignment.CenterHorizontally))
            if (request == null) {
                ShowCode(invite, fallbackEndpoint, onClose)
            } else {
                ApproveRequest(request, onRespond)
            }
        }
    }
}

@Composable
private fun ColumnScope.ShowCode(invite: PairingInvite, fallbackEndpoint: String?, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(invite.uri) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    val secondsLeft = ((invite.expiresAtMillis - now) / 1_000).coerceAtLeast(0)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(AppIcons.QrCode, tone = Tone.JADE, size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Pair with a QR code", style = MaterialTheme.typography.titleLarge)
            Text(
                "On the other device, open Send and tap Scan QR code.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    QrCodeImage(
        invite.qr,
        modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp).fillMaxWidth()
            .semantics { contentDescription = "Pairing QR code" },
    )
    Text(
        "Works once and expires in ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}. " +
            "Scanning it is all the other device needs: there is no code to compare.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (fallbackEndpoint != null) {
        InsetRow {
            Text("Can't scan? Add by IP", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(fallbackEndpoint, style = MaterialTheme.typography.bodyMedium, fontFamily = VoidArrayTheme.extras.mono)
        }
    }
    SecondaryButton("Close", onClick = onClose, modifier = Modifier.fillMaxWidth().height(50.dp))
}

@Composable
private fun ApproveRequest(request: PairingRequest, onRespond: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var armed by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) {
        delay(PAIR_ARMING_MILLIS)
        armed = true
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(deviceIcon(request.deviceType), tone = Tone.JADE, size = 56.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(request.alias, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("scanned your pairing code", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
    Text(
        "Pair only if you just scanned this code with ${request.alias}. Both devices will trust each other, so " +
            "transfers between them no longer show a code to compare.",
        style = MaterialTheme.typography.bodyMedium,
    )
    InsetRow {
        Text("Key", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Text(
            request.fingerprint.chunked(4).take(4).joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = VoidArrayTheme.extras.mono,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Decline", onClick = { onRespond(false) }, modifier = Modifier.weight(1f).height(50.dp))
        PrimaryButton("Pair", onClick = { onRespond(true) }, enabled = armed, modifier = Modifier.weight(1f).height(50.dp))
    }
}

/**
 * Draws [matrix] dark on white with the four-module quiet zone scanners need. Always light, whatever the
 * theme: many scanners cannot read an inverted code.
 */
@Composable
internal fun QrCodeImage(matrix: QrMatrix, modifier: Modifier = Modifier) {
    val quiet = 4
    Canvas(modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small).background(Color.White)) {
        val cell = size.width / (matrix.size + quiet * 2)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    // A hair of overlap stops anti-aliasing seams between neighbouring modules.
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** Progress of pairing with a code this device scanned. */
@Composable
internal fun QrPairingDialog(state: QrPairingState, onDismiss: () -> Unit) {
    val inProgress = state is QrPairingState.Connecting || state is QrPairingState.WaitingForConfirmation
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        icon = {
            when (state) {
                is QrPairingState.Paired -> IconTile(AppIcons.Lock, tone = Tone.JADE, size = 44.dp)
                is QrPairingState.Failed -> IconTile(AppIcons.Warning, tone = Tone.DANGER, size = 44.dp)
                else -> FormationArray(modifier = Modifier.size(48.dp), periodMillis = 3_000)
            }
        },
        title = {
            Text(
                when (state) {
                    QrPairingState.Connecting -> "Connecting"
                    is QrPairingState.WaitingForConfirmation -> "Confirm pairing"
                    is QrPairingState.Paired -> "Paired"
                    is QrPairingState.Failed -> "Pairing failed"
                },
            )
        },
        text = {
            Text(
                when (state) {
                    QrPairingState.Connecting -> "Checking the device in the QR code."
                    is QrPairingState.WaitingForConfirmation -> "Tap Pair on ${state.alias} to finish."
                    is QrPairingState.Paired ->
                        "This device and ${state.alias} now trust each other. Transfers between them no longer show a code to compare."
                    is QrPairingState.Failed -> state.message
                },
            )
        },
        confirmButton = {
            if (inProgress) {
                QuietButton("Cancel", onClick = onDismiss)
            } else {
                QuietButton(if (state is QrPairingState.Paired) "Done" else "Close", onClick = onDismiss)
            }
        },
    )
}

/**
 * Full-screen camera view for scanning a pairing code, with the camera feed supplied by the platform.
 * [message] explains a problem, such as a denied camera permission or a code that is not VoidArray's.
 */
@Composable
fun QrScannerScreen(
    message: String?,
    onClose: () -> Unit,
    camera: @Composable (Modifier) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        camera(Modifier.matchParentSize())
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(scheme.surfaceContainerLow)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Scan a pairing code", style = MaterialTheme.typography.titleLarge)
            Text(
                message ?: "On the other device, open Receive and tap Show pairing QR, then point this camera at it.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message != null) scheme.error else scheme.onSurfaceVariant,
            )
            SecondaryButton("Cancel", onClick = onClose, modifier = Modifier.fillMaxWidth().height(50.dp))
        }
    }
}
