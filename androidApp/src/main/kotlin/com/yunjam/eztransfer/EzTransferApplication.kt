package com.yunjam.eztransfer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.yunjam.eztransfer.engine.AppSettings
import com.yunjam.eztransfer.engine.DestinationFolder
import com.yunjam.eztransfer.engine.DeviceIdentity
import com.yunjam.eztransfer.engine.LocalDestinationFolder
import com.yunjam.eztransfer.engine.TransferEngine
import com.yunjam.eztransfer.protocol.DeviceType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Owns the engine for the life of the process so transfers survive activity recreation. */
class EzTransferApplication : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var engine: TransferEngine
        private set

    private val appScope = MainScope()
    private lateinit var wifiLock: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(SharedPreferencesStore(getSharedPreferences("eztransfer", MODE_PRIVATE)), defaultAlias())
        // First launch generates a 2048-bit RSA key here, a one-off cost well under the ANR limit.
        val identity = DeviceIdentity.loadOrCreate(File(filesDir, "identity.p12"), settings.store)
        engine = TransferEngine(
            DeviceType.MOBILE,
            settings,
            identity,
            restoreDestination(),
            historyFile = File(filesDir, "history.json"),
            log = { Log.d(TAG, it) },
        )
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiLockMode(), "EzTransfer:transfer")
            .apply { setReferenceCounted(false) }
        observeActiveTransfers()
    }

    fun useDestination(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.destination = treeUri.toString()
        engine.setDestination(SafTreeDestination(this, treeUri))
    }

    /**
     * Holds the foreground service and a Wi-Fi lock only while something is actually transferring.
     * dataSync services get a limited time budget per day, so they must not run while idle.
     */
    private fun observeActiveTransfers() {
        appScope.launch {
            engine.transfers
                .map { list -> list.any { it.isActive } }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        wifiLock.acquire()
                        TransferService.start(this@EzTransferApplication)
                    } else {
                        if (wifiLock.isHeld) wifiLock.release()
                        TransferService.stop(this@EzTransferApplication)
                    }
                }
        }
    }

    private fun restoreDestination(): DestinationFolder {
        val saved = settings.destination?.let(Uri::parse)
        if (saved != null && contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }) {
            return SafTreeDestination(this, saved)
        }
        // Until the user picks a folder, files land in app-specific storage, which needs no permission.
        val fallback = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "received")
        return LocalDestinationFolder(fallback)
    }

    private fun defaultAlias(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    @Suppress("DEPRECATION")
    private fun wifiLockMode(): Int =
        // HIGH_PERF is deprecated from API 34 and mapped to LOW_LATENCY anyway; LOW_LATENCY exists from API 29.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF

    companion object {
        const val TAG = "EzTransfer"
    }
}
