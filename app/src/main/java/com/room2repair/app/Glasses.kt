package com.room2repair.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import com.meta.wearable.dat.speech.types.TranscriptionResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything that talks to the glasses, through Meta's Wearables Device Access Toolkit:
 * one device session with the camera stream (for photos), on-glasses speech recognition
 * (voice commands) and the glasses' capture button. Follows the patterns in Meta's
 * CameraAccess and BirdSpotter samples.
 */
class Glasses(private val scope: CoroutineScope) {

  enum class Link { OFF, CONNECTING, READY }

  private val _link = MutableStateFlow(Link.OFF)
  val link: StateFlow<Link> = _link.asStateFlow()

  private val _cameraReady = MutableStateFlow(false)
  val cameraReady: StateFlow<Boolean> = _cameraReady.asStateFlow()

  private val _glassesListening = MutableStateFlow(false)
  val glassesListening: StateFlow<Boolean> = _glassesListening.asStateFlow()

  /** Set once the glasses report they can't do speech, so the phone mic takes over. */
  private val _speechUnavailable = MutableStateFlow(false)
  val speechUnavailable: StateFlow<Boolean> = _speechUnavailable.asStateFlow()

  private val _heard = MutableSharedFlow<String>(extraBufferCapacity = 16)
  /** Final transcripts from the glasses' microphones. */
  val heard: SharedFlow<String> = _heard.asSharedFlow()

  private val _buttonPresses = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
  /** A short press of the glasses' capture button (when Meta allows button input). */
  val buttonPresses: SharedFlow<Unit> = _buttonPresses.asSharedFlow()

  private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val errors: SharedFlow<String> = _errors.asSharedFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private var speech: Speech? = null
  private var inputs: Inputs? = null
  private var speechStarted = false
  private val jobs = mutableListOf<Job>()

  /** Starts the session. Camera, speech and button attach once the session reports STARTED. */
  fun start(micAllowed: Boolean) {
    if (session != null) return
    _link.value = Link.CONNECTING
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { created ->
          session = created
          jobs += scope.launch { created.errors.collect { error -> report("Glasses: ${error.description}") } }
          jobs +=
              scope.launch {
                created.state.collect { state ->
                  when (state) {
                    DeviceSessionState.STARTED -> {
                      _link.value = Link.READY
                      attachCamera(created)
                      if (micAllowed) attachSpeech(created) else _speechUnavailable.value = true
                      attachInputs(created)
                    }
                    DeviceSessionState.STOPPED -> cleanup()
                    else -> Unit
                  }
                }
              }
          // Subscribe before start() so no transition is missed.
          created.start()
        }
        .onFailure { error, _ ->
          report("Couldn't connect to the glasses: ${error.description}")
          _link.value = Link.OFF
        }
  }

  fun stop() {
    session?.stop() ?: cleanup()
  }

  private fun cleanup() {
    jobs.forEach { it.cancel() }
    jobs.clear()
    runCatching { camera?.close() }
    camera = null
    stream = null
    speech = null
    inputs = null
    session = null
    speechStarted = false
    _cameraReady.value = false
    _glassesListening.value = false
    _link.value = Link.OFF
  }

  // ---------------------------------------------------------------- camera
  private fun attachCamera(session: DeviceSession) {
    if (camera != null) return
    session
        .addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15))
        .onSuccess { added ->
          camera = added
          val s = added.stream
          stream = s
          jobs +=
              scope.launch {
                s.state.collect { st ->
                  _cameraReady.value = st == StreamState.STREAMING || st == StreamState.STARTED
                }
              }
          s.start().onFailure { error, _ -> report("Glasses camera didn't start: ${error.description}") }
        }
        .onFailure { error, _ -> report("Glasses camera unavailable: ${error.description}") }
  }

  /** Takes a photo with the glasses camera and returns it as an upright JPEG, or null. */
  suspend fun capture(): ByteArray? {
    // The camera stream can take a few seconds to come up after connecting.
    if (!_cameraReady.value) withTimeoutOrNull(8_000) { _cameraReady.first { it } }
    val s = stream ?: return null.also { report("Glasses camera isn't ready yet. Is the glasses camera allowed in Meta AI?") }
    var result: PhotoData? = null
    withTimeoutOrNull(20_000) {
      s.capturePhoto()
          .onSuccess { result = it }
          .onFailure { error, _ -> report("Photo failed: ${error.description}") }
    }
    val photo = result ?: return null
    return withContext(Dispatchers.Default) { toUprightJpeg(photo) }
  }

  // ---------------------------------------------------------------- speech
  private fun attachSpeech(session: DeviceSession) {
    if (speech != null) return
    session
        .addSpeech()
        .onSuccess { sp ->
          speech = sp
          jobs += scope.launch { sp.transcriptions.collect { onTranscript(it) } }
          jobs +=
              scope.launch {
                sp.state.collect { st ->
                  when (st) {
                    SpeechState.STARTED -> {
                      speechStarted = true
                      _glassesListening.value = true
                    }
                    // Taking a photo puts the recognizer down; start it again.
                    SpeechState.STOPPED ->
                        if (speechStarted) {
                          _glassesListening.value = false
                          reviveSpeech()
                        }
                    else -> Unit
                  }
                }
              }
          jobs +=
              scope.launch {
                sp.errors.collect { error ->
                  if (error != null) {
                    Log.w(TAG, "speech: ${error.description}")
                    if (!speechStarted) _speechUnavailable.value = true
                  }
                }
              }
          sp.start().onFailure { error, _ ->
            Log.w(TAG, "speech start: ${error.description}")
            _speechUnavailable.value = true
          }
        }
        .onFailure { error, _ ->
          Log.w(TAG, "speech attach: ${error.description}")
          _speechUnavailable.value = true
        }
  }

  private fun reviveSpeech() {
    jobs +=
        scope.launch {
          repeat(5) {
            delay(800)
            val sp = speech ?: return@launch
            if (sp.state.value != SpeechState.STOPPED) return@launch
            sp.start()
          }
        }
  }

  private fun onTranscript(result: TranscriptionResult?) {
    val r = result ?: return
    if (r.isFinal && r.text.isNotBlank()) _heard.tryEmit(r.text)
  }

  // ---------------------------------------------------------------- capture button
  private fun attachInputs(session: DeviceSession) {
    if (inputs != null) return
    session
        .addInputs(
            InputsConfiguration(
                sources = setOf(InputSource.CAPTURE_BUTTON, InputSource.CAPTOUCH),
                consumeBack = false,
            ),
        )
        .onSuccess { inp ->
          inputs = inp
          jobs +=
              scope.launch {
                inp.events.collect { event ->
                  if (
                      event is InputEvent.Capture &&
                          event.pressType == CapturePressType.SHORT_PRESS &&
                          event.source == InputSource.CAPTURE_BUTTON
                  ) {
                    _buttonPresses.tryEmit(Unit)
                  }
                }
              }
        }
        // Button input is an experimental feature Meta must approve; voice works without it.
        .onFailure { error, _ -> Log.i(TAG, "capture button not available: ${error.description}") }
  }

  private fun report(message: String) {
    Log.w(TAG, message)
    _errors.tryEmit(message)
  }

  // ---------------------------------------------------------------- photo decoding
  private fun toUprightJpeg(photo: PhotoData): ByteArray? {
    val bitmap: Bitmap =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val m = exifRotation(bytes)
            if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
          }
        }
    return jpeg(bitmap)
  }

  private fun exifRotation(bytes: ByteArray): Matrix {
    val orientation =
        runCatching {
              ByteArrayInputStream(bytes).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
              }
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return Matrix().apply {
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
      }
    }
  }

  companion object {
    private const val TAG = "Room2Repair:Glasses"

    /** Scales to at most 1280 px and encodes JPEG — small enough for the free AI tier. */
    fun jpeg(bitmap: Bitmap): ByteArray {
      val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
      val sized =
          if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
          else bitmap
      return ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    suspend fun permission(p: Permission): Boolean =
        Wearables.checkPermissionStatus(p).fold(onSuccess = { it == PermissionStatus.Granted }, onFailure = { _, _ -> false })
  }
}
