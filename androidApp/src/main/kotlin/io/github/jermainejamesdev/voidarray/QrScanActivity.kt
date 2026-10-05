package io.github.jermainejamesdev.voidarray

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.jermainejamesdev.voidarray.ui.QrScannerScreen
import io.github.jermainejamesdev.voidarray.ui.VoidArrayTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans a pairing QR code with the camera and returns its text as [EXTRA_TEXT]. Only a code that looks
 * like a VoidArray pairing link ends the scan; the engine validates it fully.
 */
class QrScanActivity : ComponentActivity() {
    private val app get() = application as VoidArrayApplication

    private var message by mutableStateOf<String?>(null)
    private lateinit var previewView: PreviewView
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reader = QRCodeReader()
    private val delivered = AtomicBoolean(false)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            message = "VoidArray needs the camera to scan a pairing code. You can add the device by IP address instead."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContent {
            val settings by app.engine.settings.collectAsState()
            VoidArrayTheme(settings.theme) {
                QrScannerScreen(message = message, onClose = ::finish) { modifier ->
                    AndroidView(factory = { previewView }, modifier = modifier)
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                val camera = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(this@QrScanActivity, camera, preview, analysis)
            } catch (e: Exception) {
                message = "Could not open the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Decodes the luminance plane; QR codes need no color, and the Y plane needs no conversion. */
    private fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            // The last row can be shorter than rowStride, so copy into a buffer sized for whole rows.
            val data = ByteArray(plane.rowStride * image.height)
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            onDecoded(reader.decode(BinaryBitmap(HybridBinarizer(source))).text)
        } catch (_: ReaderException) {
            // No readable code in this frame; the next one will be tried.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun onDecoded(text: String) {
        if (!text.startsWith(PAIRING_PREFIX)) {
            runOnUiThread { message = "That QR code is not a VoidArray pairing code." }
            return
        }
        if (!delivered.compareAndSet(false, true)) return
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
            finish()
        }
    }

    companion object {
        const val EXTRA_TEXT = "io.github.jermainejamesdev.voidarray.QR_TEXT"
        private const val PAIRING_PREFIX = "voidarray://pair"
    }
}
