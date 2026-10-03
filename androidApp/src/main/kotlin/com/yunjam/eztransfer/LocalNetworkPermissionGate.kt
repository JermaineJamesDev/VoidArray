package com.yunjam.eztransfer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yunjam.eztransfer.engine.LocalNetworkAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requests ACCESS_LOCAL_NETWORK before any socket is opened. Must be constructed during activity
 * initialization because it registers an activity-result launcher.
 *
 * The permission only exists for apps targeting SDK 37+ running on Android 17+. Below that, local
 * network access is implicitly granted through INTERNET, and the permission must not be declared or
 * requested, so [isRequired] checks both the device and this app's targetSdk.
 */
class LocalNetworkPermissionGate(private val activity: ComponentActivity) : LocalNetworkAccess {
    private var pending: CompletableDeferred<Boolean>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.complete(granted)
        pending = null
    }

    override suspend fun ensure(): Boolean {
        if (!isRequired(activity)) return true
        if (ContextCompat.checkSelfPermission(activity, PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        return withContext(Dispatchers.Main) {
            val request = pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                launcher.launch(PERMISSION)
            }
            request.await()
        }
    }

    companion object {
        // Literal because the constant only exists in the SDK 37 android.jar.
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val ANDROID_17 = 37

        fun isRequired(context: Context): Boolean =
            Build.VERSION.SDK_INT >= ANDROID_17 && context.applicationInfo.targetSdkVersion >= ANDROID_17
    }
}
