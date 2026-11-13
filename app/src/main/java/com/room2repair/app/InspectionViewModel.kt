package com.room2repair.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

data class UiState(
    val registration: RegistrationState? = null,
    val sdkReady: Boolean = false,
    val handsFree: Boolean = false,
    val mode: Mode = Mode.INSPECTION,
    val unit: String = "Unit 4B",
    val roomHint: String? = null,
    val busy: String? = null,
    val lastSaid: String = "",
    val lastHeard: String = "",
    val listeningWith: String = "off",
    val draft: Draft? = null,
    val lastBefore: String? = null,
    val lastAfter: String? = null,
    val lastRoom: String? = null,
    val log: List<String> = emptyList(),
    /** Ticket-factory progress shown during the demo: -1 = idle, otherwise the current step. */
    val stage: Int = -1,
    /** "damage", "clean" or "saved" once a photo has been processed. */
    val outcome: String? = null,
    val error: String? = null,
    /** Text for the celebration banner after approving. */
    val celebrate: String? = null,
)

class InspectionViewModel(app: Application) : AndroidViewModel(app) {
  val store = Store(app)
  private val ai = Ai(store)
  val glasses = Glasses(viewModelScope)
  private val speaker = Speaker(app)
  private val phone = PhoneListener(app) { text -> viewModelScope.launch { onHeard(text, fromGlasses = false) } }
  /** Every command waits its turn here, so nothing is dropped while the app is busy or talking. */
  private val queue = Channel<Command>(capacity = 32)
  private var lastHeardNorm = ""
  private var lastHeardAt = 0L
  private var lastCaptureAt = 0L

  private val _ui = MutableStateFlow(UiState(unit = store.unit))
  val ui: StateFlow<UiState> = _ui.asStateFlow()

  init {
    viewModelScope.launch {
      for (cmd in queue) {
        try {
          execute(cmd)
        } catch (e: Exception) {
          busy(null)
          log("Error: ${e.message ?: e.javaClass.simpleName}")
        }
      }
    }
    viewModelScope.launch {
      glasses.link.collect { link -> if (link == Glasses.Link.OFF && _ui.value.handsFree) log("Glasses disconnected.") }
    }
  }

  /** Called by MainActivity once the Android permissions are granted and the SDK is initialised. */
  fun onSdkReady() {
    if (_ui.value.sdkReady) return
    _ui.update { it.copy(sdkReady = true) }
    viewModelScope.launch { Wearables.registrationState.collect { r -> _ui.update { it.copy(registration = r) } } }
    viewModelScope.launch { glasses.heard.collect { onHeard(it, fromGlasses = true) } }
    viewModelScope.launch { glasses.buttonPresses.collect { handle(Command.Capture(null)) } }
    viewModelScope.launch { glasses.errors.collect { log(it) } }
    viewModelScope.launch {
      glasses.glassesListening.collect { on -> if (on) setListening("glasses") }
    }
    viewModelScope.launch {
      glasses.speechUnavailable.collect { unavailable ->
        if (unavailable && _ui.value.handsFree) usePhoneMic()
      }
    }
  }

  // ------------------------------------------------------------ start / stop
  /**
   * Starts hands-free mode: asks the glasses for camera + microphone access (through Meta AI the
   * first time), opens the glasses session, and greets the inspector in the glasses.
   */
  fun startHandsFree(request: suspend (Permission) -> PermissionStatus, phoneMicAllowed: Boolean) {
    viewModelScope.launch {
      if (_ui.value.registration != RegistrationState.REGISTERED) {
        log("Connect the glasses in Setup first.")
        return@launch
      }
      if (!Glasses.permission(Permission.CAMERA) && request(Permission.CAMERA) != PermissionStatus.Granted) {
        log("Camera access was not allowed in Meta AI.")
        return@launch
      }
      val mic = Glasses.permission(Permission.MICROPHONE) || request(Permission.MICROPHONE) == PermissionStatus.Granted
      phoneMicPermitted = phoneMicAllowed
      _ui.update { it.copy(handsFree = true) }
      glasses.start(micAllowed = mic)
      // Listen with the phone mic too (it hears the inspector clearly when held); duplicates are ignored.
      usePhoneMic()
      say("Room2Repair ready. ${store.unit}, ${modeName()} mode. Say take a photo when you see damage.")
    }
  }

  fun stopHandsFree() {
    glasses.stop()
    phone.stop()
    _ui.update { it.copy(handsFree = false, listeningWith = "off") }
  }

  private var phoneMicPermitted = false

  private fun usePhoneMic() {
    if (!phoneMicPermitted || phone.active) return
    if (!phone.available) {
      log("This phone has no speech recognizer — use the on-screen buttons.")
      return
    }
    phone.start()
    setListening("phone mic")
    log("Using the phone's mic for voice commands.")
  }

  /** Lets the inspector use the phone mic even when the glasses can listen. */
  fun togglePhoneMic(allowed: Boolean) {
    phoneMicPermitted = allowed
    if (phone.active) {
      phone.stop()
      setListening(if (glasses.glassesListening.value) "glasses" else "off")
    } else {
      usePhoneMic()
    }
  }

  // ------------------------------------------------------------ voice
  private suspend fun onHeard(text: String, fromGlasses: Boolean) {
    // The glasses' mics sit next to their speakers: ignore what we just said ourselves.
    if (fromGlasses && (speaker.speaking || System.currentTimeMillis() - speaker.lastFinishedAt < 1200)) return
    if (echoOfLastLine(text)) return
    // The glasses and the phone mic can both hear the same words: only act once.
    val n = Commands.norm(text)
    val now = System.currentTimeMillis()
    if (n == lastHeardNorm && now - lastHeardAt < 4_000) return
    lastHeardNorm = n
    lastHeardAt = now
    _ui.update { it.copy(lastHeard = text) }
    val cmd = Commands.parse(text) ?: return
    if (cmd is Command.Note && !noteExpected()) return // stray conversation, not for us
    handle(cmd)
  }

  private fun echoOfLastLine(text: String): Boolean {
    val heard = Commands.norm(text)
    val said = Commands.norm(_ui.value.lastSaid)
    return heard.length > 12 && said.contains(heard.take(24))
  }

  private fun noteExpected() =
      _ui.value.mode == Mode.INSPECTION && System.currentTimeMillis() - lastCaptureAt < 3 * 60_000

  fun handle(cmd: Command) {
    // Mode changes show on screen immediately, even while something else is still running.
    when (cmd) {
      Command.MoveIn -> _ui.update { it.copy(mode = Mode.MOVE_IN, draft = null, roomHint = null) }
      Command.Inspection -> _ui.update { it.copy(mode = Mode.INSPECTION, draft = null, roomHint = null) }
      else -> Unit
    }
    log("▶ " + describe(cmd))
    queue.trySend(cmd)
  }

  private fun describe(cmd: Command) =
      when (cmd) {
        is Command.Capture -> "take photo" + (cmd.room?.let { " ($it)" } ?: "")
        Command.Approve -> "approve"
        Command.Skip -> "skip"
        Command.Repeat -> "repeat"
        Command.MoveIn -> "move-in mode"
        Command.Inspection -> "inspection mode"
        Command.Status -> "tickets"
        Command.Help -> "help"
        is Command.SetUnit -> cmd.unit
        is Command.SetRoom -> "room: ${cmd.room}"
        is Command.Note -> "note: ${cmd.text}"
        is Command.PhonePhoto -> "phone photo"
      }

  private suspend fun execute(cmd: Command) {
    when (cmd) {
      is Command.Capture -> captureFromGlasses(cmd.room ?: _ui.value.roomHint)
      Command.Approve -> approve()
      Command.Skip -> {
        val had = _ui.value.draft != null
        _ui.update { it.copy(draft = null, stage = -1, outcome = null) }
        say(if (had) "Skipped. Next one." else "Nothing pending.")
      }
      Command.Repeat -> say(_ui.value.lastSaid.ifBlank { "Say take a photo when you see damage." })
      Command.MoveIn -> setMode(Mode.MOVE_IN)
      Command.Inspection -> setMode(Mode.INSPECTION)
      Command.Status -> {
        val open = store.tickets.value.filter { it.unit == store.unit && it.status != "Done" }
        say(
            if (open.isEmpty()) "No open tickets for ${store.unit}."
            else "${store.unit} has ${open.size} open: " + open.take(4).joinToString(". ") { "${it.room}, ${it.title}" } + ".",
        )
      }
      Command.Help ->
          say(
              "Say take a photo to capture damage. Then approve or skip. You can describe the damage after the photo. " +
                  "Say move in to record move-in photos, inspection to compare, unit and a number to switch units, or a room name before the photo.",
          )
      is Command.SetUnit -> {
        store.unit = cmd.unit
        _ui.update { it.copy(unit = cmd.unit, draft = null, roomHint = null) }
        say("Now on ${cmd.unit}, ${modeName()} mode.")
      }
      is Command.SetRoom -> {
        _ui.update { it.copy(roomHint = cmd.room) }
        say("${cmd.room}. Say take a photo.")
      }
      is Command.Note -> reanalyzeWithNote(cmd.text)
      is Command.PhonePhoto -> processPhoto(cmd.bytes, _ui.value.roomHint)
    }
  }

  private suspend fun setMode(mode: Mode) {
    _ui.update { it.copy(mode = mode, draft = null, roomHint = null) }
    if (mode == Mode.MOVE_IN) {
      say("Move-in mode for ${store.unit}. Take a photo of each room and I'll save it as the move-in record. Say inspection when you're done.")
    } else {
      val n = store.baselines.value.count { it.unit.equals(store.unit, true) }
      say("Inspection mode for ${store.unit}, with $n move-in photos on file. Take a photo of anything that looks damaged.")
    }
  }

  // ------------------------------------------------------------ capture → AI → draft
  private fun stage(n: Int) = _ui.update { it.copy(stage = n) }

  private suspend fun captureFromGlasses(room: String?) {
    _ui.update { it.copy(stage = 0, outcome = null, error = null, draft = null, celebrate = null) }
    busy("Taking photo with the glasses…")
    val bytes = glasses.capture()
    if (bytes == null) {
      fail("I couldn't get a photo from the glasses. Try again.")
      return
    }
    processPhoto(bytes, room)
  }

  /** Backup path: a photo taken with the phone camera (testing without glasses). */
  fun onPhonePhoto(bytes: ByteArray) = handle(Command.PhonePhoto(bytes))

  private suspend fun processPhoto(bytes: ByteArray, roomHint: String?) {
    val path = store.savePhoto(bytes)
    lastCaptureAt = System.currentTimeMillis()
    _ui.update {
      it.copy(lastAfter = path, lastBefore = null, draft = null, stage = 1, outcome = null, error = null, celebrate = null, lastRoom = null)
    }
    sayQuick("Got it.")

    if (_ui.value.mode == Mode.MOVE_IN) {
      busy("Saving to the move-in album…")
      // Named by voice ("kitchen", then "take a photo") or numbered — no AI call, so the free limit is kept for inspections.
      val room = roomHint ?: "Spot ${store.baselinesFor(store.unit).size + 1}"
      val label = store.addBaseline(store.unit, room, path)
      busy(null)
      _ui.update { it.copy(lastRoom = label, roomHint = null, stage = 2, outcome = "saved") }
      say("Saved move-in photo: $label. Take the next one, or say inspection when you're done.")
      return
    }
    _ui.update { it.copy(roomHint = null) }
    analyze(roomHint, path, note = "")
  }

  /**
   * The "ticket factory": compares the new photo with the unit's move-in photos, drafts a ticket
   * priced for Atlanta, GA, reads it out and waits for approval. The on-screen steps advance while
   * the AI works so the audience can follow along.
   */
  private suspend fun analyze(roomHint: String?, afterPath: String?, note: String) {
    val unit = store.unit
    val all = store.baselinesFor(unit)
    val hinted = roomHint?.let { h -> all.filter { it.room.startsWith(h, ignoreCase = true) } }.orEmpty()
    val candidates = (hinted + all).distinctBy { it.photo }.take(1)
    val fmt = SimpleDateFormat("MMM d", Locale.US)
    val befores = candidates.mapNotNull { b -> store.readPhoto(b.photo)?.let { BeforePhoto(b.room, fmt.format(Date(b.takenAt)), it) } }

    stage(1)
    busy(if (befores.isEmpty()) "No move-in photos yet — checking for visible damage…" else "Matching against ${befores.size} move-in photo${if (befores.size > 1) "s" else ""}…")
    val ticker =
        viewModelScope.launch {
          for (s in 2..4) {
            delay(1400)
            if (_ui.value.stage < s) stage(s)
          }
        }
    val result =
        try {
          ai.compare(unit = unit, roomHint = roomHint, befores = befores, after = store.readPhoto(afterPath), note = note)
        } catch (e: Exception) {
          ticker.cancel()
          fail(errorLine(e))
          return
        }
    ticker.cancel()
    val match = result.matched?.let { candidates.getOrNull(it) }
    val room = roomHint ?: result.room ?: match?.room?.replace(Regex(" \\d+$"), "") ?: "Unit"
    _ui.update { it.copy(lastBefore = match?.photo, lastRoom = room) }
    // Finish the remaining steps quickly so the audience sees each one tick.
    for (s in (_ui.value.stage + 1).coerceAtLeast(2)..4) {
      stage(s)
      delay(450)
    }
    busy(null)

    if (result.issues.isEmpty()) {
      _ui.update { it.copy(draft = null, stage = 5, outcome = "clean") }
      say("$room: no new damage found${if (match != null) " compared with move-in" else ""}. No ticket needed. If you can see damage, describe it and I'll write the ticket.")
      return
    }
    val draft = Draft(unit, room, result.issues, note, match?.photo, afterPath, result.summary)
    _ui.update { it.copy(draft = draft, stage = 5, outcome = "damage") }
    val head = if (result.issues.size == 1) "$room, new damage." else "$room, ${result.issues.size} new issues."
    val body =
        result.issues.mapIndexed { i, issue -> (if (result.issues.size > 1) "${i + 1}. " else "") + issue.spoken() }.joinToString(" ")
    val warn = if (match == null) " There's no matching move-in photo, so double-check before charging the tenant." else ""
    say("$head $body$warn Say approve, or skip.")
  }

  private suspend fun fail(message: String) {
    busy(null)
    _ui.update { it.copy(stage = -1, error = message) }
    say(message)
  }

  private suspend fun reanalyzeWithNote(note: String) {
    val after = _ui.value.lastAfter
    if (after == null || _ui.value.mode != Mode.INSPECTION) return
    sayQuick("Adding that.")
    _ui.update { it.copy(stage = 1, draft = null, outcome = null, error = null) }
    analyze(_ui.value.lastRoom, after, note)
  }

  private suspend fun approve() {
    val d = _ui.value.draft
    if (d == null) {
      say("Nothing to approve yet. Take a photo of the damage first.")
      return
    }
    val saved = store.addTickets(d)
    val ids = saved.joinToString(" and ") { "#${it.id}" }
    _ui.update {
      it.copy(
          draft = null,
          stage = -1,
          outcome = null,
          celebrate = "${if (saved.size > 1) "Tickets $ids" else "Ticket $ids"} sent to maintenance!",
      )
    }
    say("Approved. ${if (saved.size > 1) "Tickets ${ids.replace("#", "")} are" else "Ticket ${ids.replace("#", "")} is"} with maintenance. Next one.")
  }

  fun dismissCelebration() = _ui.update { it.copy(celebrate = null) }

  fun clearMoveIn() {
    store.clearBaselines(store.unit)
    log("Cleared move-in photos for ${store.unit}.")
  }

  // On-screen buttons (backup for voice)
  fun approveNow() = handle(Command.Approve)

  fun skipNow() = handle(Command.Skip)

  fun captureNow() = handle(Command.Capture(null))

  fun setModeNow(mode: Mode) = handle(if (mode == Mode.MOVE_IN) Command.MoveIn else Command.Inspection)

  fun setUnitNow(unit: String) {
    store.unit = unit
    _ui.update { it.copy(unit = store.unit, draft = null) }
  }

  fun setRoomHint(room: String?) = _ui.update { it.copy(roomHint = room) }

  // ------------------------------------------------------------ speaking & status
  private suspend fun say(text: String) {
    _ui.update { it.copy(lastSaid = text) }
    log("🔊 $text")
    phone.pause()
    speaker.say(text)
    phone.resume()
  }

  /** A short acknowledgement that doesn't hold up the work. */
  private fun sayQuick(text: String) {
    viewModelScope.launch {
      phone.pause()
      speaker.say(text)
      phone.resume()
    }
  }

  private fun busy(text: String?) = _ui.update { it.copy(busy = text) }

  private fun setListening(with: String) = _ui.update { it.copy(listeningWith = with) }

  private fun modeName() = if (_ui.value.mode == Mode.MOVE_IN) "move-in" else "inspection"

  private fun errorLine(e: Exception) =
      when {
        e is AiException -> e.message ?: "AI error."
        e.message == "rate limited" -> "The free AI is busy for a moment. Wait about 30 seconds and try again."
        else -> "I couldn't reach the AI. Check the internet connection and try again."
      }

  fun log(line: String) {
    val time = SimpleDateFormat("h:mm:ss", Locale.US).format(Date())
    _ui.update { it.copy(log = (listOf("$time  $line") + it.log).take(40)) }
  }

  override fun onCleared() {
    super.onCleared()
    glasses.stop()
    phone.stop()
    speaker.shutdown()
  }
}
