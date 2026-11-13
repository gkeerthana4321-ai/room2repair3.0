package com.room2repair.app

import org.json.JSONArray
import org.json.JSONObject

val ROOMS =
    listOf(
        "Living Room", "Kitchen", "Bathroom", "Bedroom", "Hallway",
        "Balcony", "Laundry", "Dining Room", "Closet", "Garage",
    )

val TRADES =
    listOf(
        "General", "Painting/Drywall", "Flooring", "Plumbing", "Electrical", "Appliance",
        "Carpentry", "HVAC", "Cleaning", "Pest", "Windows/Doors",
    )

enum class Mode { MOVE_IN, INSPECTION }

/** One problem the AI found, before the inspector approves it. */
data class Issue(
    val title: String,
    val description: String,
    val trade: String,
    val priority: String,
    val costLow: Int,
    val costHigh: Int,
    val responsibility: String,
    val confidence: Double,
) {
  /** How the glasses read it out. */
  fun spoken(): String {
    val who =
        when (responsibility) {
          "Tenant" -> "tenant pays"
          "Owner" -> "owner pays"
          else -> "needs review"
        }
    return "$title. $priority priority, ${trade.replace("/", " and ")}, " +
        "about $costLow to $costHigh dollars, $who."
  }

  companion object {
    fun fromAi(o: JSONObject): Issue {
      fun pick(v: String, allowed: List<String>, d: String) = if (v in allowed) v else d
      val lo = o.optDouble("cost_low", 0.0).toInt().coerceAtLeast(0)
      val hi = o.optDouble("cost_high", 0.0).toInt().coerceAtLeast(lo)
      return Issue(
          title = o.optString("title", "Repair needed").ifBlank { "Repair needed" }.take(80),
          description = o.optString("description", ""),
          trade = pick(o.optString("trade"), TRADES, "General"),
          priority = pick(o.optString("priority"), listOf("High", "Medium", "Low"), "Medium"),
          costLow = lo,
          costHigh = hi,
          responsibility = pick(o.optString("responsibility"), listOf("Tenant", "Owner", "Review"), "Review"),
          confidence = o.optDouble("confidence", 0.6).coerceIn(0.0, 1.0),
      )
    }
  }
}

/** A drafted ticket waiting for "approve" or "skip". */
data class Draft(
    val unit: String,
    val room: String,
    val issues: List<Issue>,
    val note: String,
    val beforePhoto: String?,
    val afterPhoto: String?,
    val summary: String,
)

data class Ticket(
    val id: Long,
    val unit: String,
    val room: String,
    val title: String,
    val description: String,
    val trade: String,
    val priority: String,
    val costLow: Int,
    val costHigh: Int,
    val responsibility: String,
    val confidence: Double,
    val note: String,
    val beforePhoto: String?,
    val afterPhoto: String,
    val status: String,
    val createdAt: Long,
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("id", id).put("unit", unit).put("room", room).put("title", title)
          .put("description", description).put("trade", trade).put("priority", priority)
          .put("costLow", costLow).put("costHigh", costHigh).put("responsibility", responsibility)
          .put("confidence", confidence).put("note", note)
          .put("beforePhoto", beforePhoto ?: JSONObject.NULL).put("afterPhoto", afterPhoto)
          .put("status", status).put("createdAt", createdAt)

  fun shareText(): String =
      "[$priority] $title\nUnit: $unit — $room\nTrade: $trade\nDetails: $description\n" +
          "Est. cost: $$costLow–$$costHigh · ${responsibility} pays\n" +
          (if (note.isNotBlank()) "Inspector said: \"$note\"\n" else "") +
          "Created with Room2Repair (Meta glasses inspection)"

  companion object {
    fun fromJson(o: JSONObject) =
        Ticket(
            id = o.getLong("id"),
            unit = o.optString("unit"),
            room = o.optString("room"),
            title = o.optString("title"),
            description = o.optString("description"),
            trade = o.optString("trade", "General"),
            priority = o.optString("priority", "Medium"),
            costLow = o.optInt("costLow"),
            costHigh = o.optInt("costHigh"),
            responsibility = o.optString("responsibility", "Review"),
            confidence = o.optDouble("confidence", 0.6),
            note = o.optString("note"),
            beforePhoto = if (o.isNull("beforePhoto")) null else o.optString("beforePhoto"),
            afterPhoto = o.optString("afterPhoto"),
            status = o.optString("status", "Open"),
            createdAt = o.optLong("createdAt"),
        )
  }
}

data class Baseline(val unit: String, val room: String, val photo: String, val takenAt: Long)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** Finds a room name inside free text ("the kitchen sink" -> Kitchen). */
fun matchRoom(text: String): String? {
  val t = text.lowercase()
  ROOMS.firstOrNull { t.contains(it.lowercase()) }?.let { return it }
  val aliases =
      mapOf(
          "living" to "Living Room", "lounge" to "Living Room", "bath" to "Bathroom",
          "restroom" to "Bathroom", "toilet" to "Bathroom", "washroom" to "Bathroom",
          "bed" to "Bedroom", "hall" to "Hallway", "dining" to "Dining Room",
          "patio" to "Balcony", "laundry" to "Laundry",
      )
  return aliases.entries.firstOrNull { Regex("\\b${it.key}").containsMatchIn(t) }?.value
}
