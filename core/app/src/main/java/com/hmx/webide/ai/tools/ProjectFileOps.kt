package com.hmx.webide.ai.tools

import org.json.JSONObject
import java.io.File

/**
 * Project-scoped file operations the AI can invoke through tools.
 *
 * Every path is resolved against [root] (the currently opened project) and is
 * rejected unless it stays strictly inside it (no absolute paths, no `..`
 * traversal). The application, not the model's free-form text, performs the
 * actual filesystem write — this is what makes file editing reliable.
 */
class ProjectFileOps(private val root: File) {

  /** Number of successful write/delete operations performed this session. */
  var changedFiles = 0
    private set

  fun dispatch(call: com.hmx.webide.ai.models.ToolCall): String = runCatching {
    val args = JSONObject(call.arguments.ifBlank { "{}" })
    when (call.name) {
      "read_file" -> read(args.getString("path"))
      "write_file" -> write(args.getString("path"), args.optString("content", ""))
      "list_files" -> list(args.optString("path", "."))
      "delete_file" -> delete(args.getString("path"))
      else -> "Error: unknown tool '${call.name}'"
    }
  }.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}" }

  private fun read(rel: String): String {
    val file = resolve(rel) ?: return "Error: invalid or out-of-project path '$rel'"
    if (!file.isFile) return "Error: file not found: $rel"
    return runCatching { file.readText() }.getOrDefault("Error: could not read $rel")
  }

  private fun write(rel: String, content: String): String {
    val file = resolve(rel) ?: return "Error: invalid or out-of-project path '$rel'"
    runCatching { file.parentFile?.mkdirs() }.getOrElse { return "Error: cannot create parent dirs for $rel" }
    runCatching { file.writeText(content) }.getOrElse { return "Error: could not write $rel" }
    changedFiles++
    return "Wrote ${content.length} chars to $rel"
  }

  private fun list(rel: String): String {
    val dir = resolve(rel) ?: return "Error: invalid or out-of-project path '$rel'"
    if (!dir.isDirectory) return "Error: not a directory: $rel"
    val entries = dir.listFiles() ?: return "Error: could not list $rel"
    return entries.sortedBy { it.name }.joinToString("\n") {
      "${if (it.isDirectory) "[dir] " else ""}${it.name}"
    }
  }

  private fun delete(rel: String): String {
    val file = resolve(rel) ?: return "Error: invalid or out-of-project path '$rel'"
    if (!file.isFile) return "Error: not a file (refusing to delete directory): $rel"
    runCatching { file.delete() }.getOrElse { return "Error: could not delete $rel" }
    changedFiles++
    return "Deleted $rel"
  }

  /** Resolves a possibly-relative path and guarantees it stays inside [root]. */
  private fun resolve(rel: String): File? {
    val cleaned = rel.trim().removePrefix("/").removePrefix("./")
    if (cleaned.isEmpty() || cleaned.contains("..")) return null
    if (File(cleaned).isAbsolute) return null
    val file = File(root, cleaned)
    val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return null
    val fileCanonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
    if (fileCanonical != rootCanonical && !fileCanonical.startsWith("$rootCanonical${File.separator}")) {
      return null
    }
    return file
  }
}
