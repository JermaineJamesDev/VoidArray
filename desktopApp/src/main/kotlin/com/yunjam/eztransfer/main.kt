package com.yunjam.eztransfer

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yunjam.eztransfer.core.PlatformActions
import com.yunjam.eztransfer.engine.AppSettings
import com.yunjam.eztransfer.engine.LocalDestinationFolder
import com.yunjam.eztransfer.engine.LocalFileHandle
import com.yunjam.eztransfer.engine.PropertiesFileStore
import com.yunjam.eztransfer.engine.TransferEngine
import com.yunjam.eztransfer.protocol.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.InetAddress
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() {
    // Native look for the Swing folder chooser; AWT's FileDialog is already native.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val settings = AppSettings(PropertiesFileStore(File(appDataDir(), "settings.properties")), defaultAlias())
    val destination = settings.destination?.let(::File) ?: defaultDownloadDir()
    val engine = TransferEngine(DeviceType.DESKTOP, settings, LocalDestinationFolder(destination))

    application {
        val windowState = rememberWindowState(size = DpSize(820.dp, 860.dp))
        Window(
            onCloseRequest = {
                engine.close()
                exitApplication()
            },
            title = "EzTransfer",
            state = windowState,
        ) {
            val scope = rememberCoroutineScope()
            val actions = remember { DesktopActions(window, engine, settings, scope) }
            LaunchedEffect(Unit) { engine.start { true } }
            App(engine, actions)
        }
    }
}

private class DesktopActions(
    private val owner: Frame,
    private val engine: TransferEngine,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) : PlatformActions {
    override fun pickFilesToSend() {
        val dialog = FileDialog(owner, "Choose files to send", FileDialog.LOAD).apply {
            isMultipleMode = true
            isVisible = true
        }
        engine.stage(dialog.files.filter { it.isFile }.map(::LocalFileHandle))
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

    override fun startNetworking() {
        scope.launch { engine.start { true } }
    }
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
