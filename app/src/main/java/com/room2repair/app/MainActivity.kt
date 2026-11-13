package com.room2repair.app

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  private val vm: InspectionViewModel by viewModels()

  // Same start-up sequence as Meta's CameraAccess sample: Android permissions, then the SDK.
  private val permissionCheckLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
          Wearables.initialize(this).onFailure { error, _ -> Log.e(TAG, "DAT init failed: ${error.description}") }
          vm.onSdkReady()
        } else {
          vm.log("Room2Repair needs Bluetooth permission to reach the glasses.")
        }
      }

  // Glasses permissions (camera, microphone) are granted in the Meta AI app.
  private var wearableContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  private val wearablesPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        wearableContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        wearableContinuation = null
      }

  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { cont ->
          wearableContinuation = cont
          cont.invokeOnCancellation { wearableContinuation = null }
          wearablesPermissionLauncher.launch(permission)
        }
      }

  private var micContinuation: CancellableContinuation<Boolean>? = null
  private val micLauncher =
      registerForActivityResult(RequestPermission()) { granted ->
        micContinuation?.resume(granted)
        micContinuation = null
      }

  /** Phone microphone, for backup voice commands. */
  suspend fun requestPhoneMic(): Boolean {
    if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
    return permissionMutex.withLock {
      suspendCancellableCoroutine { cont ->
        micContinuation = cont
        cont.invokeOnCancellation { micContinuation = null }
        micLauncher.launch(RECORD_AUDIO)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      Room2RepairApp(
          vm = vm,
          requestWearablesPermission = ::requestWearablesPermission,
          requestPhoneMic = ::requestPhoneMic,
          connectGlasses = { Wearables.startRegistration(this) },
          disconnectGlasses = { Wearables.startUnregistration(this) },
      )
    }
  }

  override fun onStart() {
    super.onStart()
    permissionCheckLauncher.launch(arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET))
  }

  companion object {
    private const val TAG = "Room2Repair"
  }
}
