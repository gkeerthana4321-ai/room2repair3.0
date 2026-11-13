package com.room2repair.app

/** What the inspector can say. Plain Kotlin, no Android, so it's easy to reason about. */
sealed class Command {
  /** "take a photo" — optionally naming the room ("take a photo of the kitchen"). */
  data class Capture(val room: String?) : Command()
  data object Approve : Command()
  data object Skip : Command()
  data object Repeat : Command()
  data object MoveIn : Command()
  data object Inspection : Command()
  data object Status : Command()
  data object Help : Command()
  data class SetUnit(val unit: String) : Command()
  data class SetRoom(val room: String) : Command()
  /** Anything else: a description of the damage. */
  data class Note(val text: String) : Command()
  /** A photo taken with the phone camera (backup when glasses aren't available). */
  class PhonePhoto(val bytes: ByteArray) : Command()
}

object Commands {
  private val CAPTURE =
      Regex("\\b(take|snap|capture|grab|get)\\b.*\\b(photo|picture|pic|shot|image)\\b|^(capture|snap|photo|picture|shoot|click)$")
  private val APPROVE =
      Regex("^(approve|approved|yes|yeah|yep|confirm|confirmed|send it|looks good|save it)\\b")
  private val SKIP = Regex("^(skip|no|nope|discard|cancel|reject|delete|ignore)\\b")
  private val STATUS = Regex("^(tickets|status|summary|how many|what s open|whats open)\\b")
  private val REPEAT = Regex("^(repeat|say again|say that again|come again|what)\\b")
  private val MOVE_IN = Regex("\\bmove ?-?in\\b")
  private val INSPECT = Regex("\\b(move ?-?out|inspection|inspect|walkthrough|walk through)\\b")
  private val HELP = Regex("^(help|commands|what can i say)\\b")
  private val UNIT = Regex("^(?:switch to |go to |set )?(?:unit|apartment|apt)\\s+([a-z0-9-]+(?:\\s[a-z0-9])?)$")

  fun norm(text: String) =
      text.lowercase().replace(Regex("[^a-z0-9\\s-]"), " ").replace(Regex("\\s+"), " ").trim()

  fun parse(raw: String): Command? {
    val t = norm(raw)
    if (t.isEmpty()) return null
    val words = t.split(" ")

    if (words.size <= 10 && CAPTURE.containsMatchIn(t)) return Command.Capture(matchRoom(t))

    // Other commands must be short, so a description like "the shower door won't close"
    // isn't mistaken for a command.
    if (words.size <= 5) {
      if (HELP.containsMatchIn(t)) return Command.Help
      if (STATUS.containsMatchIn(t)) return Command.Status
      if (APPROVE.containsMatchIn(t)) return Command.Approve
      if (SKIP.containsMatchIn(t)) return Command.Skip
      if (REPEAT.containsMatchIn(t)) return Command.Repeat
      if (MOVE_IN.containsMatchIn(t)) return Command.MoveIn
      if (INSPECT.containsMatchIn(t)) return Command.Inspection
      UNIT.find(t)?.let { return Command.SetUnit("Unit " + it.groupValues[1].replace(" ", "").uppercase()) }
      if (words.size <= 3) matchRoom(t)?.let { return Command.SetRoom(it) }
    }
    // Ignore filler ("um", "okay so") the recognizer picks up.
    if (words.size < 3) return null
    return Command.Note(raw.trim())
  }
}
