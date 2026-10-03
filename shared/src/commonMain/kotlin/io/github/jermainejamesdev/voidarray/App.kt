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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import io.github.jermainejamesdev.voidarray.ui.DeviceSettingsCard
import io.github.jermainejamesdev.voidarray.ui.DevicesCard
import io.github.jermainejamesdev.voidarray.ui.EmptyState
import io.github.jermainejamesdev.voidarray.ui.FormationArray
import io.github.jermainejamesdev.voidarray.ui.InkDivider
import io.github.jermainejamesdev.voidarray.ui.VoidArrayTheme
import io.github.jermainejamesdev.voidarray.ui.HistoryHeader
import io.github.jermainejamesdev.voidarray.ui.HistoryRow
import io.github.jermainejamesdev.voidarray.ui.IconBadge
import io.github.jermainejamesdev.voidarray.ui.IncomingOfferDialog
import io.github.jermainejamesdev.voidarray.ui.ReceiveFolderCard
import io.github.jermainejamesdev.voidarray.ui.ReceiveStatusCard
import io.github.jermainejamesdev.voidarray.ui.ReceivingSettingsCard
import io.github.jermainejamesdev.voidarray.ui.SecuritySettingsCard
import io.github.jermainejamesdev.voidarray.ui.SendContentCard
import io.github.jermainejamesdev.voidarray.ui.TransferCard

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

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                val twoColumn = maxWidth >= 1040.dp
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            header = {
                                Image(
                                    rememberVectorPainter(AppIcons.Logo),
                                    contentDescription = "VoidArray",
                                    modifier = Modifier.padding(vertical = 12.dp).size(48.dp),
                                )
                            },
                        ) {
                            Destination.entries.forEach { item ->
                                NavigationRailItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { NavIcon(item, badges[item] ?: 0) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            DestinationContent(destination, controller, actions, twoColumn)
                            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().imePadding()) {
                        Box(modifier = Modifier.weight(1f)) {
                            DestinationContent(destination, controller, actions, twoColumn = false)
                            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                        NavigationBar {
                            Destination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { NavIcon(item, badges[item] ?: 0) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
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
private fun NavIcon(item: Destination, badge: Int) {
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(badge.toString()) } }) { Icon(item.icon, contentDescription = null) }
    } else {
        Icon(item.icon, contentDescription = null)
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    controller: TransferController,
    actions: PlatformActions,
    twoColumn: Boolean,
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (destination != Destination.HISTORY) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScreenTitle(destination.label)
                        InkDivider(modifier = Modifier.widthIn(max = 220.dp))
                    }
                }
            }
            statusBanners(local, controller, actions)

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
                        )
                    }
                    if (twoColumn) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    item { ReceiveStatusCard(local, local.deviceType) }
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
                    item { HistoryHeader(hasEntries = history.isNotEmpty(), onClear = controller::clearHistory) }
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

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
}

private fun LazyListScope.statusBanners(local: LocalStatus, controller: TransferController, actions: PlatformActions) {
    local.problem?.let { problem ->
        item(key = "problem") {
            Banner(
                problem,
                BannerKind.ERROR,
                action = if (!local.serverRunning) {
                    { Button(onClick = actions::startNetworking) { Text("Try again") } }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transfers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (transfers.any { !it.isActive }) {
                TextButton(onClick = controller::clearFinishedTransfers) { Text("Clear finished") }
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
        modifier = Modifier.fillMaxSize().background(scheme.surface.copy(alpha = 0.88f)).padding(24.dp)
            .border(2.dp, scheme.secondary, MaterialTheme.shapes.extraLarge),
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
