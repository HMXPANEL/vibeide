package com.hmx.webide.activities.aichat

import java.io.File
import org.json.JSONObject

/**
 * Persists the active AI task for a project at `<project>/.androidide/chat_task.json`,
 * reusing the same project-cache directory convention as [ChatHistoryStore]. Only one
 * task is active per project at a time.
 */
object ChatTaskStore {

  fun file(projectDir: File): File =
    File(File(projectDir, ".androidide"), "chat_task.json")

  fun load(projectDir: File): ChatTask? {
    val f = file(projectDir)
    if (!f.isFile) return null
    return runCatching {
      val o = JSONObject(f.readText())
      ChatTask(
        id = o.optString("id", ""),
        prompt = o.optString("prompt", ""),
        status = runCatching {
          ChatTask.Status.valueOf(o.optString("status", "INTERRUPTED"))
        }.getOrDefault(ChatTask.Status.INTERRUPTED),
        partial = o.optString("partial", ""),
        error = o.optString("error", "").ifBlank { null },
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
      )
    }.getOrNull()
  }

  fun save(projectDir: File, task: ChatTask) {
    runCatching {
      val dir = File(projectDir, ".androidide")
      if (!dir.isDirectory) dir.mkdirs()
      val o = JSONObject()
        .put("id", task.id)
        .put("prompt", task.prompt)
        .put("status", task.status.name)
        .put("partial", task.partial)
        .put("error", task.error ?: "")
        .put("createdAt", task.createdAt)
        .put("updatedAt", task.updatedAt)
      file(projectDir).writeText(o.toString())
    }
  }

  fun clear(projectDir: File) {
    runCatching { file(projectDir).delete() }
  }
}
