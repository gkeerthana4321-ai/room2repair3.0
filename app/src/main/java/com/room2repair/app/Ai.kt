package com.room2repair.app

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AiException(message: String) : Exception(message)

/** What the AI found. [matched] is the index of the move-in photo showing the same spot, if any. */
data class Comparison(val summary: String, val issues: List<Issue>, val room: String?, val matched: Int?)

/** A move-in photo offered to the AI as a possible "before". */
class BeforePhoto(val label: String, val takenAt: String, val jpeg: ByteArray)

/**
 * Approximate 2026 repair prices for the Atlanta, Georgia area, given to the AI so its estimates
 * are consistent. Ballpark figures for a class demo — not quotes.
 */
val GEORGIA_PRICE_GUIDE =
    """Price everything for the Atlanta, Georgia metro area in 2026 (approximate, labor + materials):
- Labor: handyman ${'$'}65–95/hr; painter ${'$'}45–70/hr; licensed plumber ${'$'}110–160/hr; licensed electrician ${'$'}100–150/hr; HVAC tech ${'$'}100–150/hr. Most trades charge a ${'$'}100–150 minimum service call.
- Small drywall hole/dent patch + paint: ${'$'}150–350. Large hole or water-damaged drywall section: ${'$'}350–900.
- Repaint one wall: ${'$'}200–450. Repaint a full room: ${'$'}400–1,000.
- Carpet stain deep-clean: ${'$'}75–175. Replace carpet in one room: ${'$'}700–1,600. Vinyl plank repair: ${'$'}150–400.
- Leaky faucet/supply line repair: ${'$'}150–325. Running toilet repair: ${'$'}125–275. Clogged drain: ${'$'}125–300.
- Ceiling water stain (find source, repair, paint): ${'$'}300–1,000.
- Broken/missing outlet or switch cover: ${'$'}10–40 DIY, ${'$'}100–180 by electrician. Dead outlet troubleshooting: ${'$'}150–300.
- Interior door repair/rehang: ${'$'}120–300. Replace interior door: ${'$'}250–550.
- Window blind replacement: ${'$'}40–150. Window screen repair: ${'$'}40–100. Cracked window pane: ${'$'}200–500.
- Cabinet door/hinge repair: ${'$'}80–200. Countertop burn/chip repair: ${'$'}150–400.
- Appliance repair visit: ${'$'}150–350. Smoke detector replacement: ${'$'}50–120.
- Deep cleaning one room: ${'$'}75–150; whole unit: ${'$'}250–500. Pest treatment: ${'$'}150–300."""

class Ai(private val store: Store) {

  /** Which room is this? Used when the inspector doesn't say it. */
  suspend fun identifyRoom(photo: ByteArray): String {
    val content =
        JSONArray()
            .put(image(photo))
            .put(text("Which room of a home is this? Answer with JSON {\"room\": one of ${JSONArray(ROOMS)}}."))
    val out = chat(content, 60)
    return matchRoom(out.optString("room")) ?: "Living Room"
  }

  /**
   * Looks at the photo taken now next to the unit's move-in photos, works out which move-in photo
   * shows the same spot, and drafts a ticket for anything new — priced for Atlanta, GA.
   */
  suspend fun compare(
      unit: String,
      roomHint: String?,
      befores: List<BeforePhoto>,
      after: ByteArray?,
      note: String,
  ): Comparison {
    val letters = "ABCD"
    val content = JSONArray()
    befores.take(4).forEachIndexed { i, b ->
      content.put(text("MOVE-IN PHOTO ${letters[i]}: ${b.label}, taken ${b.takenAt}."))
      content.put(image(b.jpeg))
    }
    if (after != null) {
      content.put(text("NOW PHOTO: taken just now during the inspection${roomHint?.let { " (inspector says: $it)" } ?: ""}."))
      content.put(image(after))
    }
    val matchLine =
        if (befores.isEmpty()) "There are NO move-in photos for this unit. Treat any clearly visible damage in the NOW photo as new, with lower confidence."
        else "Step 1: decide which MOVE-IN PHOTO (${letters.take(befores.size.coerceAtMost(4)).toList().joinToString("/")}) shows the same area as the NOW photo (same wall, fixture or furniture). If none matches, use null.\nStep 2: compare the NOW photo with that matched move-in photo."
    content.put(
        text(
            """
You are Room2Repair, which inspects rental units and drafts maintenance tickets. $unit.
$matchLine
Inspector's spoken note: "${note.ifBlank { "(none)" }.replace("\"", "'")}"

Report damage or problems that are NEW compared with move-in (or, with no matching move-in photo, clearly visible damage). Ignore lighting and camera-angle differences and normal wear and tear. Treat the inspector's note as strong evidence: if the inspector describes damage, include it.
One ticket per issue. Titles short and easy to read aloud (under 8 words).
responsibility: "Tenant" = tenant-caused beyond normal wear, "Owner" = wear, age or building systems, "Review" = unclear.
priority: "High" = safety, active leak, security or habitability; "Medium" = fix before next tenant; "Low" = cosmetic.

$GEORGIA_PRICE_GUIDE

Reply with ONLY JSON:
{"matched_move_in":"A" or null,"room":"name of the room or area","summary":"one sentence","issues":[{"title":"","description":"1-2 sentences for the maintenance tech, including where in the room","trade":"one of ${TRADES.joinToString(" | ")}","priority":"High|Medium|Low","cost_low":0,"cost_high":0,"responsibility":"Tenant|Owner|Review","confidence":0.0}]}
"""
                .trimIndent(),
        ),
    )
    val out = chat(content, 1400)
    val issues = (out.optJSONArray("issues") ?: JSONArray()).objects().map(Issue::fromAi)
    val m = out.optString("matched_move_in", "").trim().uppercase()
    val matched = if (m.length == 1 && m[0] in letters) letters.indexOf(m[0]).takeIf { it < befores.size } else null
    val room = out.optString("room", "").trim().takeIf { it.isNotBlank() && it.lowercase() != "null" }?.take(40)
    return Comparison(out.optString("summary"), issues, room, matched)
  }

  private fun text(t: String) = JSONObject().put("type", "text").put("text", t)

  private fun image(jpeg: ByteArray) =
      JSONObject()
          .put("type", "image_url")
          .put(
              "image_url",
              JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)),
          )

  private suspend fun chat(content: JSONArray, maxTokens: Int): JSONObject =
      withContext(Dispatchers.IO) {
        val key = store.groqKey
        if (key.isBlank()) throw AiException("Add your free Groq key in Setup first.")
        val body =
            JSONObject()
                .put("model", store.visionModel)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                .put("temperature", 0.2)
                .put("max_completion_tokens", maxTokens)
                .put("response_format", JSONObject().put("type", "json_object"))
        val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        try {
          conn.requestMethod = "POST"
          conn.connectTimeout = 15_000
          conn.readTimeout = 60_000
          conn.doOutput = true
          conn.setRequestProperty("Authorization", "Bearer $key")
          conn.setRequestProperty("Content-Type", "application/json")
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          val code = conn.responseCode
          val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
          if (code !in 200..299) {
            throw AiException(
                when (code) {
                  401 -> "The Groq key isn't valid. Check it in Setup."
                  429 -> "Free AI limit reached for now. Wait a minute and try again."
                  404, 400 -> if (text.contains("model")) "The AI model name changed. Update it in Setup." else "AI request rejected ($code)."
                  else -> "AI error $code."
                },
            )
          }
          val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "{}")
          val json = Regex("\\{[\\s\\S]*\\}").find(msg)?.value ?: msg
          JSONObject(json)
        } finally {
          conn.disconnect()
        }
      }
}
