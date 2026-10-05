package io.github.jermainejamesdev.voidarray

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.jermainejamesdev.voidarray.core.PlatformActions
import io.github.jermainejamesdev.voidarray.ui.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as VoidArrayApplication

    private val networkGate = LocalNetworkPermissionGate(this)
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        stageUris(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) app.useDestination(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val scanQr = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra(QrScanActivity.EXTRA_TEXT)
        if (result.resultCode == RESULT_OK && text != null) app.engine.pairWithQr(text)
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    private val actions = object : PlatformActions {
        override val appVersion: String = BuildConfig.VERSION_NAME
        override val supportsTray = false
        override val supportsDragAndDrop = false

        // A getter because this object is built before the activity is attached to its context.
        override val supportsQrScanning: Boolean
            get() = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        // "Remove animations" in accessibility settings sets the animator scale to 0.
        override val reduceMotion: Boolean
            get() = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        override fun pickFilesToSend() = pickFiles.launch(arrayOf("*/*"))
        override fun pickDestinationFolder() = pickFolder.launch(null)
        override fun scanPairingQr() = scanQr.launch(Intent(this@MainActivity, QrScanActivity::class.java))

        override fun openReceivedFolder() {
            val tree = (app.engine.currentDestination as? SafTreeDestination)?.treeUri
            if (tree == null) {
                app.engine.notice("Files are in app storage. Choose a folder under Receive to browse them in your file manager.")
                return
            }
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                app.engine.notice("No file manager on this device can open folders directly.")
            }
        }

        override fun copyToClipboard(text: String) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VoidArray", text))
            // Android 13+ shows its own copy confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) app.engine.notice("Copied to clipboard")
        }

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
            val settings by app.engine.settings.collectAsState()
            val dark = isDarkTheme(settings.theme)
            // The in-app theme can differ from the system's, so status and navigation bar icons follow it.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
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
        multicastLock = wifi.createMulticastLock("VoidArray:discovery").apply {
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
        // Text and links shared from a browser or notes app arrive as EXTRA_TEXT with no stream.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrBlank()) {
            app.engine.notice("Nothing that VoidArray can send was shared.")
            return
        }
        if (!text.isNullOrBlank()) app.engine.setStagedText(text)
        stageUris(uris)
    }

    private fun stageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val handles = withContext(Dispatchers.IO) {
                uris.filter(::isForeignContent).mapNotNull { ContentUriFileHandle.from(contentResolver, it) }
            }
            if (handles.size < uris.size) {
                app.engine.notice("${uris.size - handles.size} item(s) could not be read and were skipped.")
            }
            app.engine.stage(handles)
        }
    }

    /**
     * Share intents come from any app, and VoidArray opens their URIs with its own permissions. A file://
     * URI or one of this app's own content authorities could therefore point at its private files, such
     * as the identity key, and get them sent to another device. Only other apps' content URIs are read.
     */
    private fun isForeignContent(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(packageName) == false

    private companion object {
        // The scrim colors enableEdgeToEdge uses by default for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
