package io.github.jermainejamesdev.voidarray

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jermainejamesdev.voidarray.core.LocalStatus
import io.github.jermainejamesdev.voidarray.core.PlatformActions
import io.github.jermainejamesdev.voidarray.core.TransferController
import io.github.jermainejamesdev.voidarray.core.TransferDirection
import io.github.jermainejamesdev.voidarray.core.TransferState
import io.github.jermainejamesdev.voidarray.ui.AboutCard
import io.github.jermainejamesdev.voidarray.ui.AppIcons
import io.github.jermainejamesdev.voidarray.ui.AppearanceSettingsCard
import io.github.jermainejamesdev.voidarray.ui.Banner
import io.github.jermainejamesdev.voidarray.ui.BannerKind
import io.github.jermainejamesdev.voidarray.ui.ClearHistoryAction
import io.github.jermainejamesdev.voidarray.ui.DeviceSettingsCard
import io.github.jermainejamesdev.voidarray.ui.DevicesCard
import io.github.jermainejamesdev.voidarray.ui.EmptyState
import io.github.jermainejamesdev.voidarray.ui.FormationArray
import io.github.jermainejamesdev.voidarray.ui.HistoryRow
import io.github.jermainejamesdev.voidarray.ui.IncomingOfferDialog
import io.github.jermainejamesdev.voidarray.ui.InkDivider
import io.github.jermainejamesdev.voidarray.ui.PrimaryButton
import io.github.jermainejamesdev.voidarray.ui.QuietButton
import io.github.jermainejamesdev.voidarray.ui.ReceiveFolderCard
import io.github.jermainejamesdev.voidarray.ui.ReceiveStatusCard
import io.github.jermainejamesdev.voidarray.ui.ReceivingSettingsCard
import io.github.jermainejamesdev.voidarray.ui.SecuritySettingsCard
import io.github.jermainejamesdev.voidarray.ui.SendContentCard
import io.github.jermainejamesdev.voidarray.ui.TransferCard
import io.github.jermainejamesdev.voidarray.ui.VoidArrayTheme

internal enum class Destination(val label: String, val icon: ImageVector) {
    SEND("Send", AppIcons.Send),
    RECEIVE("Receive", AppIcons.Download),
    HISTORY("History", AppIcons.History),
    SETTINGS("Settings", AppIcons.Settings),
}

/**
 * Root UI. Uses a navigation rail on wide windows (desktop, tablets, landscape) and a bottom bar on
 * phones. [isDropTarget] is set by the desktop host while files are dragged over the window.
 */
@Composable
fun App(controller: TransferController, actions: PlatformActions, isDropTarget: Boolean = false) {
    AppRoot(controller, actions, isDropTarget, Destination.SEND)
}

/** [App] with a chosen starting screen, for the screenshot renderer. */
@Composable
internal fun AppRoot(
    controller: TransferController,
    actions: PlatformActions,
    isDropTarget: Boolean,
    startDestination: Destination,
) {
    val settings by controller.settings.collectAsState()
    VoidArrayTheme(settings.theme, reduceMotion = actions.reduceMotion) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val transfers by controller.transfers.collectAsState()
            val offer by controller.incomingOffer.collectAsState()
            val local by controller.local.collectAsState()
            var destination by rememberSaveable { mutableStateOf(startDestination) }

            // Notices are transient ("Copied", "Could not reach..."), so they appear as snackbars that stay
            // visible regardless of scroll position, then clear themselves.
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(local.notice) {
                val notice = local.notice ?: return@LaunchedEffect
                snackbar.showSnackbar(notice, withDismissAction = true)
                controller.dismissNotice()
            }

            // Jump to Receive when an incoming transfer starts so its progress is visible right away.
            val activeIncoming = transfers.filter { it.direction == TransferDirection.RECEIVE && it.isActive }.map { it.id }
            var seenIncoming by remember { mutableStateOf(emptySet<String>()) }
            LaunchedEffect(activeIncoming) {
                if (activeIncoming.any { it !in seenIncoming }) destination = Destination.RECEIVE
                seenIncoming = seenIncoming + activeIncoming
            }

            val badges = mapOf(
                Destination.SEND to transfers.count { it.direction == TransferDirection.SEND && it.isActive },
                Destination.RECEIVE to transfers.count { it.direction == TransferDirection.RECEIVE && it.isActive },
            )
            val snackbarHost = @Composable { modifier: Modifier ->
                SnackbarHost(snackbar, modifier = modifier) { data -> Snackbar(data, shape = MaterialTheme.shapes.small) }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                val twoColumn = maxWidth >= 1040.dp
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavRail(destination, badges, onSelect = { destination = it })
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            DestinationContent(destination, controller, actions, wide = true, twoColumn, onNavigate = { destination = it })
                            snackbarHost(Modifier.align(Alignment.BottomCenter))
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().imePadding()) {
                        Box(modifier = Modifier.weight(1f)) {
                            DestinationContent(destination, controller, actions, wide = false, twoColumn = false, onNavigate = { destination = it })
                            snackbarHost(Modifier.align(Alignment.BottomCenter))
                        }
                        NavBar(destination, badges, onSelect = { destination = it })
                    }
                }
            }

            offer?.let {
                IncomingOfferDialog(
                    offer = it,
                    onRespond = controller::respondToOffer,
                    onCopy = actions::copyToClipboard,
                )
            }

            if (isDropTarget) DropOverlay()
        }
    }
}

@Composable
private fun NavRail(selected: Destination, badges: Map<Destination, Int>, onSelect: (Destination) -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().width(92.dp)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .padding(vertical = 20.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            rememberVectorPainter(AppIcons.Logo),
            contentDescription = "VoidArray",
            modifier = Modifier.padding(bottom = 18.dp).size(44.dp),
        )
        Destination.entries.forEach { item ->
            val isSelected = item == selected
            Column(
                modifier = Modifier.width(72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item) })
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NavIcon(item, badges[item] ?: 0, isSelected)
                NavLabel(item.label, isSelected)
            }
        }
    }
}

@Composable
private fun NavBar(selected: Destination, badges: Map<Destination, Int>, onSelect: (Destination) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(72.dp).selectableGroup(),
            ) {
                Destination.entries.forEach { item ->
                    val isSelected = item == selected
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // A shaped background rather than a clip, so the badge can overhang the indicator.
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 18.dp, vertical = 4.dp),
                        ) {
                            NavIcon(item, badges[item] ?: 0, isSelected)
                        }
                        Spacer(Modifier.height(4.dp))
                        NavLabel(item.label, isSelected)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavIcon(item: Destination, badge: Int, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box {
        Icon(item.icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
        if (badge > 0) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)
                    .size(16.dp).clip(CircleShape).background(scheme.secondary),
                contentAlignment = Alignment.Center,
            ) {
                // The inherited body line height is taller than the badge and would push the digit out of it.
                Text(badge.toString(), color = scheme.onSecondary, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NavLabel(text: String, selected: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DestinationContent(
    destination: Destination,
    controller: TransferController,
    actions: PlatformActions,
    wide: Boolean,
    twoColumn: Boolean,
    onNavigate: (Destination) -> Unit,
) {
    val local by controller.local.collectAsState()
    val settings by controller.settings.collectAsState()
    val peers by controller.peers.collectAsState()
    val staged by controller.staged.collectAsState()
    val stagedText by controller.stagedText.collectAsState()
    val transfers by controller.transfers.collectAsState()
    val history by controller.history.collectAsState()
    val trusted by controller.trustedDevices.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = if (twoColumn) 1200.dp else 760.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                ScreenHeader(
                    title = destination.label,
                    status = if (wide) local else null,
                    action = if (destination == Destination.HISTORY && history.isNotEmpty()) {
                        { ClearHistoryAction(onClear = controller::clearHistory) }
                    } else {
                        null
                    },
                )
            }
            statusBanners(local, actions)

            when (destination) {
                Destination.SEND -> {
                    val canSend = (staged.isNotEmpty() || stagedText.isNotBlank()) && local.serverRunning
                    val content = @Composable {
                        SendContentCard(
                            staged = staged,
                            stagedText = stagedText,
                            supportsDragAndDrop = actions.supportsDragAndDrop,
                            onAddFiles = actions::pickFilesToSend,
                            onRemove = controller::removeStaged,
                            onClear = controller::clearStaged,
                            onTextChange = controller::setStagedText,
                        )
                    }
                    val devices = @Composable {
                        DevicesCard(
                            peers = peers,
                            canSend = canSend,
                            scanning = local.scanning,
                            enabled = local.serverRunning,
                            onSend = controller::sendStaged,
                            onRescan = controller::rescan,
                            onAddManual = controller::addManualPeer,
                            onReview = { onNavigate(Destination.SETTINGS) },
                        )
                    }
                    if (twoColumn) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Box(modifier = Modifier.weight(1f)) { content() }
                                Box(modifier = Modifier.weight(1f)) { devices() }
                            }
                        }
                    } else {
                        item { content() }
                        item { devices() }
                    }
                    transferSection(transfers.filter { it.direction == TransferDirection.SEND }, controller, actions)
                }

                Destination.RECEIVE -> {
                    item { ReceiveStatusCard(local, local.deviceType, onCopy = actions::copyToClipboard) }
                    transferSection(transfers.filter { it.direction == TransferDirection.RECEIVE }, controller, actions)
                    item {
                        ReceiveFolderCard(
                            destinationLabel = local.destinationLabel,
                            onChange = actions::pickDestinationFolder,
                            onOpen = actions::openReceivedFolder,
                        )
                    }
                }

                Destination.HISTORY -> {
                    if (history.isEmpty()) {
                        item {
                            EmptyState(
                                icon = AppIcons.History,
                                title = "No records yet",
                                body = "Files and messages you send or receive are recorded here.",
                            )
                        }
                    }
                    items(history, key = { it.id }) { entry ->
                        HistoryRow(entry, onCopy = actions::copyToClipboard, onOpenFolder = actions::openReceivedFolder)
                    }
                }

                Destination.SETTINGS -> {
                    item { DeviceSettingsCard(settings, onAliasChange = controller::setAlias) }
                    item {
                        ReceivingSettingsCard(
                            settings = settings,
                            destinationLabel = local.destinationLabel,
                            onChangeFolder = actions::pickDestinationFolder,
                            onAutoAcceptChange = controller::setAutoAcceptTrusted,
                        )
                    }
                    item {
                        AppearanceSettingsCard(
                            settings = settings,
                            supportsTray = actions.supportsTray,
                            onThemeChange = controller::setTheme,
                            onTrayChange = controller::setMinimizeToTray,
                        )
                    }
                    item {
                        SecuritySettingsCard(
                            fingerprint = local.fingerprint,
                            trusted = trusted,
                            onForget = { controller.forgetDevice(it.deviceId) },
                        )
                    }
                    item { AboutCard(actions.appVersion) }
                }
            }
        }
    }
}

/** The Cinzel screen title over a brush stroke, with this device's receive status on wide windows. */
@Composable
private fun ScreenHeader(title: String, status: LocalStatus?, action: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            InkDivider(modifier = Modifier.width(140.dp))
        }
        action?.invoke()
        if (status != null) StatusPill(status)
    }
}

@Composable
private fun StatusPill(local: LocalStatus) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.clip(MaterialTheme.shapes.small).background(scheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (local.serverRunning) scheme.primary else scheme.error))
        Text(if (local.serverRunning) "Ready to receive" else "Not receiving", style = MaterialTheme.typography.bodyMedium)
        val endpoint = local.addresses.firstOrNull()?.let { "$it:${local.port}" }
        if (local.serverRunning && endpoint != null) {
            Box(Modifier.width(1.dp).height(14.dp).background(scheme.outlineVariant))
            Text(endpoint, style = MaterialTheme.typography.bodyMedium, fontFamily = VoidArrayTheme.extras.mono, color = scheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.statusBanners(local: LocalStatus, actions: PlatformActions) {
    local.problem?.let { problem ->
        item(key = "problem") {
            Banner(
                problem,
                BannerKind.ERROR,
                action = if (!local.serverRunning) {
                    { PrimaryButton("Try again", onClick = actions::startNetworking) }
                } else {
                    null
                },
            )
        }
    }
    local.networkWarning?.let { warning ->
        item(key = "networkWarning") { Banner(warning, BannerKind.WARNING) }
    }
}

private fun LazyListScope.transferSection(
    transfers: List<TransferState>,
    controller: TransferController,
    actions: PlatformActions,
) {
    if (transfers.isEmpty()) return
    item(key = "transfersHeader") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (transfers.any { !it.isActive }) {
                QuietButton("Clear finished", onClick = controller::clearFinishedTransfers)
            }
        }
    }
    items(transfers, key = { it.id }) { transfer ->
        TransferCard(
            transfer = transfer,
            onCancel = { controller.cancelTransfer(transfer.id) },
            onCopyText = actions::copyToClipboard,
        )
    }
}

@Composable
private fun DropOverlay() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().background(scheme.surface.copy(alpha = 0.9f)).padding(24.dp)
            .border(1.5.dp, scheme.secondary.copy(alpha = 0.7f), MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FormationArray(modifier = Modifier.size(160.dp), periodMillis = 4_000)
            Text(
                "Release to gather these files",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
