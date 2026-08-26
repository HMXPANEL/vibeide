package com.hmx.webide.activities.aichat

import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.Role
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-project chat persistence.
 *
 * History lives inside the project itself at `<project>/.androidide/chat_history.json`,
 * reusing the existing project-cache directory convention — so every project owns exactly
 * one conversation and it survives leaving the screen and restarting the app.
 */
object ChatHistoryStore {

  private const val MAX_MESSAGES = 50 // mirrors ChatEngine.maxHistory

  fun file(projectDir: File): File =
    File(File(projectDir, ".androidide"), "chat_history.json")

  fun load(projectDir: File): List<ChatMessage> {
    val f = file(projectDir)
    if (!f.isFile) return emptyList()
    return runCatching {
      val arr = JSONArray(f.readText())
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val role = when (o.optString("role")) {
          "user" -> Role.user
          "assistant" -> Role.assistant
          else -> return@mapNotNull null // system/unknown entries are not shown
        }
        ChatMessage(role, o.optString("content"))
      }
    }.getOrDefault(emptyList())
  }

  /** Keeps only user/assistant turns and the last [MAX_MESSAGES] entries. */
  fun save(projectDir: File, history: List<ChatMessage>) {
    runCatching {
      val dir = File(projectDir, ".androidide")
      if (!dir.isDirectory) dir.mkdirs()
      val visible = history.filter {
        it.role == Role.user || it.role == Role.assistant
      }.takeLast(MAX_MESSAGES)
      val arr = JSONArray()
      visible.forEach { m ->
        arr.put(JSONObject().put("role", m.role.name).put("content", m.content))
      }
      file(projectDir).writeText(arr.toString())
    }
  }

  fun clear(projectDir: File) {
    runCatching { file(projectDir).delete() }
  }
}
