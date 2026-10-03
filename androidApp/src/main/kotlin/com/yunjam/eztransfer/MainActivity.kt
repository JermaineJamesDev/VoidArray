package com.yunjam.eztransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.yunjam.eztransfer.core.PlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as EzTransferApplication

    private val networkGate = LocalNetworkPermissionGate(this)
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        stageUris(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) app.useDestination(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var multicastLock: WifiManager.MulticastLock? = null

    private val actions = object : PlatformActions {
        override fun pickFilesToSend() = pickFiles.launch(arrayOf("*/*"))
        override fun pickDestinationFolder() = pickFolder.launch(null)
        override fun startNetworking() {
            lifecycleScope.launch {
                app.engine.start(networkGate)
                requestNotificationPermissionIfNeeded()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(app.engine, actions)
        }
        // On recreation the share intent was already handled; handling it again would stage duplicates.
        if (savedInstanceState == null) handleShareIntent(intent)
        actions.startNetworking()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Without this lock many Wi-Fi drivers filter inbound multicast and discovery fails silently.
        // Held only while visible because it costs battery.
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("EzTransfer:discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStop() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
        super.onStop()
    }

    private fun handleShareIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) {
            app.engine.notice("Only files can be shared to EzTransfer.")
            return
        }
        stageUris(uris)
    }

    private fun stageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val handles = withContext(Dispatchers.IO) {
                uris.mapNotNull { ContentUriFileHandle.from(contentResolver, it) }
            }
            if (handles.size < uris.size) {
                app.engine.notice("${uris.size - handles.size} item(s) could not be read and were skipped.")
            }
            app.engine.stage(handles)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
