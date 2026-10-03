package com.yunjam.eztransfer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.yunjam.eztransfer.core.PlatformActions
import com.yunjam.eztransfer.core.TransferDirection
import com.yunjam.eztransfer.engine.AppSettings
import com.yunjam.eztransfer.engine.DeviceIdentity
import com.yunjam.eztransfer.engine.LocalDestinationFolder
import com.yunjam.eztransfer.engine.LocalFileHandle
import com.yunjam.eztransfer.engine.PropertiesFileStore
import com.yunjam.eztransfer.engine.TransferEngine
import com.yunjam.eztransfer.protocol.DeviceType
import com.yunjam.eztransfer.ui.AppIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.InetAddress
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() {
    // Native look for the Swing folder chooser; AWT's FileDialog is already native.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val dataDir = appDataDir()
    val store = PropertiesFileStore(File(dataDir, "settings.properties"))
    val settings = AppSettings(store, defaultAlias())
    val identity = DeviceIdentity.loadOrCreate(File(dataDir, "identity.p12"), store)
    val destination = settings.destination?.let(::File) ?: defaultDownloadDir()
    val engine = TransferEngine(
        DeviceType.DESKTOP,
        settings,
        identity,
        LocalDestinationFolder(destination),
        historyFile = File(dataDir, "history.json"),
    )

    application {
        val userSettings by engine.settings.collectAsState()
        val offer by engine.incomingOffer.collectAsState()
        val transfers by engine.transfers.collectAsState()
        var windowVisible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val logo = rememberVectorPainter(AppIcons.Logo)
        val traySupported = remember { isTraySupported }

        fun quit() {
            engine.close()
            exitApplication()
        }

        if (traySupported) {
            Tray(
                icon = logo,
                state = trayState,
                tooltip = "EzTransfer",
                onAction = { windowVisible = true },
                menu = {
                    Item("Open EzTransfer", onClick = { windowVisible = true })
                    Item("Quit", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = {
                if (traySupported && userSettings.minimizeToTray) windowVisible = false else quit()
            },
            visible = windowVisible,
            title = "EzTransfer",
            icon = logo,
            state = rememberWindowState(size = DpSize(1120.dp, 800.dp)),
        ) {
            val scope = rememberCoroutineScope()
            val actions = remember { DesktopActions(window, engine, settings, scope, traySupported) }
            LaunchedEffect(Unit) { engine.start { true } }
            LaunchedEffect(Unit) { watchWindowsNetworkProfile(engine) }

            // An offer needs an answer within the prompt timeout, so surface the window and notify.
            LaunchedEffect(offer?.id) {
                val current = offer ?: return@LaunchedEffect
                if (!windowVisible || !window.isFocused) {
                    val what = if (current.isTextOnly) "a message" else "${current.files.size} file(s)"
                    trayState.sendNotification(
                        Notification("EzTransfer", "${current.sender.alias} wants to send you $what", Notification.Type.Info),
                    )
                }
                windowVisible = true
                window.toFront()
            }

            // Auto-accepted transfers have no prompt, so tell the user when one starts in the background.
            var notified by remember { mutableStateOf(emptySet<String>()) }
            LaunchedEffect(transfers) {
                val fresh = transfers.filter { it.direction == TransferDirection.RECEIVE && it.id !in notified }
                if (fresh.isNotEmpty() && (!windowVisible || !window.isFocused)) {
                    fresh.forEach {
                        trayState.sendNotification(Notification("EzTransfer", "Receiving from ${it.peerAlias}", Notification.Type.Info))
                    }
                }
                notified = notified + fresh.map { it.id }
            }

            var dragging by remember { mutableStateOf(false) }
            val dropTarget = remember { FileDropTarget(onHover = { dragging = it }, onFiles = actions::stageFiles) }
            @OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
            Box(
                modifier = Modifier.fillMaxSize().dragAndDropTarget(
                    shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
                    target = dropTarget,
                ),
            ) {
                App(engine, actions, isDropTarget = dragging)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class FileDropTarget(
    private val onHover: (Boolean) -> Unit,
    private val onFiles: (List<File>) -> Unit,
) : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = onHover(true)
    override fun onExited(event: DragAndDropEvent) = onHover(false)
    override fun onEnded(event: DragAndDropEvent) = onHover(false)

    override fun onDrop(event: DragAndDropEvent): Boolean {
        onHover(false)
        val data = event.dragData() as? DragData.FilesList ?: return false
        onFiles(data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() })
        return true
    }
}

private class DesktopActions(
    private val owner: Frame,
    private val engine: TransferEngine,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    override val supportsTray: Boolean,
) : PlatformActions {
    override val supportsDragAndDrop = true

    override fun pickFilesToSend() {
        val dialog = FileDialog(owner, "Choose files to send", FileDialog.LOAD).apply {
            isMultipleMode = true
            isVisible = true
        }
        stageFiles(dialog.files.toList())
    }

    fun stageFiles(files: List<File>) {
        val (regular, other) = files.partition { it.isFile }
        engine.stage(regular.map(::LocalFileHandle))
        if (other.any { it.isDirectory }) {
            engine.notice("Folders can't be sent yet. Open the folder and select the files inside.")
        }
    }

    override fun pickDestinationFolder() {
        // FileDialog cannot select directories on Windows, so this one uses Swing.
        val chooser = JFileChooser(settings.destination ?: defaultDownloadDir().path).apply {
            dialogTitle = "Save received files to"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            val folder = chooser.selectedFile
            settings.destination = folder.absolutePath
            engine.setDestination(LocalDestinationFolder(folder))
        }
    }

    override fun openReceivedFolder() {
        val folder = (engine.currentDestination as? LocalDestinationFolder)?.directory ?: return
        scope.launch(Dispatchers.IO) {
            folder.mkdirs()
            runCatching { Desktop.getDesktop().open(folder) }
                .onFailure { engine.notice("Could not open ${folder.path}") }
        }
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        engine.notice("Copied to clipboard")
    }

    override fun startNetworking() {
        scope.launch { engine.start { true } }
    }
}

/**
 * Windows blocks inbound discovery on networks marked Public, which looks exactly like a broken app.
 * Polls the connection profiles and surfaces a warning naming the affected adapters.
 */
private suspend fun watchWindowsNetworkProfile(engine: TransferEngine) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    while (true) {
        engine.setNetworkWarning(publicNetworkWarning())
        delay(60_000)
    }
}

private suspend fun publicNetworkWarning(): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-NetConnectionProfile | Select-Object InterfaceAlias,NetworkCategory | ConvertTo-Csv -NoTypeInformation",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching null
        }
        val publicAdapters = output.lines().drop(1).mapNotNull { line ->
            val columns = line.trim().removeSurrounding("\"").split("\",\"")
            columns.takeIf { it.size == 2 && it[1] == "Public" }?.get(0)
        }
        if (publicAdapters.isEmpty()) {
            null
        } else {
            "Windows treats ${publicAdapters.joinToString(", ")} as a Public network, which stops other devices from finding this PC. " +
                "Change it to Private in Settings > Network & internet > (your connection) > Network profile type."
        }
    }.getOrNull()
}

private fun appDataDir(): File {
    val appData = System.getenv("APPDATA")
    return if (appData != null) File(appData, "EzTransfer") else File(System.getProperty("user.home"), ".eztransfer")
}

private fun defaultDownloadDir(): File =
    File(System.getProperty("user.home"), "Downloads${File.separator}EzTransfer")

private fun defaultAlias(): String =
    System.getenv("COMPUTERNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "Desktop"
