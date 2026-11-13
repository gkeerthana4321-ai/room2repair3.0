package com.room2repair.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Text-to-speech. With the glasses connected over Bluetooth they are the phone's audio output,
 * so everything said here plays in the glasses' open-ear speakers.
 */
class Speaker(context: Context) {
  private val ready = CompletableDeferred<Boolean>()
  private val waiting = ConcurrentHashMap<String, () -> Unit>()
  private val ids = AtomicLong()

  @Volatile var speaking = false
    private set

  /** When the last line finished — used to ignore the glasses mic hearing our own voice. */
  @Volatile var lastFinishedAt = 0L
    private set

  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

  init {
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            speaking = true
          }

          override fun onDone(utteranceId: String?) = finish(utteranceId)

          override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)

          override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) = finish(utteranceId)
        },
    )
  }

  private fun finish(id: String?) {
    speaking = waiting.size > 1
    lastFinishedAt = System.currentTimeMillis()
    id?.let { waiting.remove(it)?.invoke() }
  }

  /** Speaks and waits until finished (or 30 s). */
  suspend fun say(text: String) {
    // Never hang if the phone's speech engine doesn't start.
    if (text.isBlank() || withTimeoutOrNull(4_000) { ready.await() } != true) return
    tts.setLanguage(Locale.US)
    tts.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    val id = "line-${ids.incrementAndGet()}"
    withTimeoutOrNull(30_000) {
      suspendCancellableCoroutine { cont ->
        waiting[id] = { if (cont.isActive) cont.resume(Unit) }
        speaking = true
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) {
          finish(id)
        }
        cont.invokeOnCancellation { waiting.remove(id) }
      }
    }
  }

  fun stop() {
    tts.stop()
    speaking = false
  }

  fun shutdown() = tts.shutdown()
}

/**
 * Backup voice commands through the phone's own speech recognizer, for when the glasses'
 * speech capability isn't available. Listens continuously and restarts itself.
 * Must be driven from the main thread.
 */
class PhoneListener(private val context: Context, private val onFinal: (String) -> Unit) {
  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  @Volatile var active = false
    private set
  @Volatile private var paused = false

  val available: Boolean
    get() = SpeechRecognizer.isRecognitionAvailable(context)

  private val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
      }

  fun start() =
      main.post {
        if (active || !available) return@post
        active = true
        paused = false
        recognizer =
            SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        listen()
      }

  fun stop() =
      main.post {
        active = false
        recognizer?.destroy()
        recognizer = null
      }

  /** Stop hearing while the app is talking, so it doesn't hear itself. */
  fun pause() =
      main.post {
        paused = true
        recognizer?.cancel()
      }

  fun resume() =
      main.post {
        if (!active) return@post
        paused = false
        listen()
      }

  private fun listen() {
    if (!active || paused) return
    try {
      recognizer?.startListening(intent)
    } catch (_: Exception) {
      main.postDelayed({ listen() }, 1000)
    }
  }

  private val listener =
      object : RecognitionListener {
        override fun onResults(results: Bundle?) {
          val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (!text.isNullOrBlank()) onFinal(text)
          main.postDelayed({ listen() }, 250)
        }

        override fun onError(error: Int) {
          // NO_MATCH / SPEECH_TIMEOUT happen constantly while nobody talks — just listen again.
          val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 300L
          main.postDelayed({ listen() }, delay)
        }

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
}
