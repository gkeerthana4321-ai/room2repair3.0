package com.room2repair.app

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything is kept on the phone: tickets and move-in records as JSON files, photos as JPEGs.
 * No accounts, no server, no cost.
 */
class Store(context: Context) {
  private val dir = context.filesDir
  private val photoDir = File(dir, "photos").apply { mkdirs() }
  private val ticketsFile = File(dir, "tickets.json")
  private val baselinesFile = File(dir, "baselines.json")
  private val prefs = context.getSharedPreferences("room2repair", Context.MODE_PRIVATE)
  private val lock = Any()

  private val _tickets = MutableStateFlow(loadTickets())
  val tickets: StateFlow<List<Ticket>> = _tickets.asStateFlow()

  private val _baselines = MutableStateFlow(loadBaselines())
  val baselines: StateFlow<List<Baseline>> = _baselines.asStateFlow()

  // ---- settings
  var groqKey: String
    get() = prefs.getString("groqKey", "") ?: ""
    set(v) = prefs.edit().putString("groqKey", v.trim()).apply()

  var unit: String
    get() = prefs.getString("unit", "Unit 4B") ?: "Unit 4B"
    set(v) = prefs.edit().putString("unit", v.trim().ifBlank { "Unit 4B" }).apply()

  var visionModel: String
    get() = prefs.getString("visionModel", DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
    set(v) = prefs.edit().putString("visionModel", v.trim().ifBlank { DEFAULT_VISION_MODEL }).apply()

  // ---- photos
  fun savePhoto(jpeg: ByteArray): String {
    val f = File(photoDir, "${System.currentTimeMillis()}.jpg")
    f.writeBytes(jpeg)
    return f.absolutePath
  }

  fun readPhoto(path: String?): ByteArray? = path?.let { File(it) }?.takeIf { it.exists() }?.readBytes()

  // ---- move-in baselines
  fun baseline(unit: String, room: String): Baseline? =
      _baselines.value.firstOrNull { it.unit.equals(unit, true) && it.room == room }

  fun setBaseline(unit: String, room: String, photo: String) =
      synchronized(lock) {
        val list =
            _baselines.value.filterNot { it.unit.equals(unit, true) && it.room == room } +
                Baseline(unit, room, photo, System.currentTimeMillis())
        _baselines.value = list
        val arr = JSONArray()
        list.forEach {
          arr.put(JSONObject().put("unit", it.unit).put("room", it.room).put("photo", it.photo).put("takenAt", it.takenAt))
        }
        baselinesFile.writeText(arr.toString())
      }

  fun baselinesFor(unit: String): List<Baseline> =
      _baselines.value.filter { it.unit.equals(unit, true) }.sortedByDescending { it.takenAt }

  /** Saves a move-in photo. A second photo of the same room becomes "Kitchen 2", so nothing is overwritten. */
  fun addBaseline(unit: String, room: String, photo: String): String {
    val existing = baselinesFor(unit).map { it.room }.toSet()
    var label = room
    var n = 2
    while (label in existing) label = "$room ${n++}"
    setBaseline(unit, label, photo)
    return label
  }

  fun clearBaselines(unit: String) =
      synchronized(lock) {
        val list = _baselines.value.filterNot { it.unit.equals(unit, true) }
        _baselines.value = list
        val arr = JSONArray()
        list.forEach {
          arr.put(JSONObject().put("unit", it.unit).put("room", it.room).put("photo", it.photo).put("takenAt", it.takenAt))
        }
        baselinesFile.writeText(arr.toString())
      }

  // ---- tickets
  fun addTickets(draft: Draft): List<Ticket> =
      synchronized(lock) {
        var next = (_tickets.value.maxOfOrNull { it.id } ?: 0L) + 1
        val now = System.currentTimeMillis()
        val added =
            draft.issues.map { i ->
              Ticket(
                  id = next++, unit = draft.unit, room = draft.room, title = i.title,
                  description = i.description, trade = i.trade, priority = i.priority,
                  costLow = i.costLow, costHigh = i.costHigh, responsibility = i.responsibility,
                  confidence = i.confidence, note = draft.note, beforePhoto = draft.beforePhoto,
                  afterPhoto = draft.afterPhoto ?: "", status = "Open", createdAt = now,
              )
            }
        writeTickets(added.reversed() + _tickets.value)
        added
      }

  fun setStatus(id: Long, status: String) =
      synchronized(lock) { writeTickets(_tickets.value.map { if (it.id == id) it.copy(status = status) else it }) }

  fun delete(id: Long) = synchronized(lock) { writeTickets(_tickets.value.filterNot { it.id == id }) }

  fun csv(): String {
    fun q(v: Any?) = "\"" + v.toString().replace("\"", "\"\"") + "\""
    val head = "ID,Unit,Room,Title,Description,Trade,Priority,Cost low,Cost high,Who pays,Status,Inspector note"
    return (listOf(head) +
            _tickets.value.map {
              listOf(it.id, it.unit, it.room, it.title, it.description, it.trade, it.priority,
                  it.costLow, it.costHigh, it.responsibility, it.status, it.note).joinToString(",") { v -> q(v) }
            })
        .joinToString("\n")
  }

  private fun writeTickets(list: List<Ticket>) {
    _tickets.value = list
    ticketsFile.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
  }

  private fun loadTickets(): List<Ticket> =
      runCatching { JSONArray(ticketsFile.readText()).objects().map(Ticket::fromJson) }.getOrDefault(emptyList())

  private fun loadBaselines(): List<Baseline> =
      runCatching {
            JSONArray(baselinesFile.readText()).objects().map {
              Baseline(it.getString("unit"), it.getString("room"), it.getString("photo"), it.getLong("takenAt"))
            }
          }
          .getOrDefault(emptyList())

  companion object {
    const val DEFAULT_VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"
  }
}
