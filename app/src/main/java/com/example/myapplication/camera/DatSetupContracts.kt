package com.example.myapplication.camera

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

/** Android permissions required before initializing the Meta DAT SDK. */
val datAndroidPermissions: Array<String>
    get() = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)

/**
 * Keeps Meta SDK types inside the camera package while allowing the Activity to launch the
 * wearable-camera permission flow through the normal Activity Result API.
 */
class DatCameraPermissionContract : ActivityResultContract<Unit, Boolean>() {
    private val delegate = Wearables.RequestPermissionContract()

    override fun createIntent(context: Context, input: Unit): Intent =
        delegate.createIntent(context, Permission.CAMERA)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        delegate.parseResult(resultCode, intent)
            .getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted
}
