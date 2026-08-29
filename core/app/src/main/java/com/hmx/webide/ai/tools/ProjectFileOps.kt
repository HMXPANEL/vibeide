package com.hmx.webide.ai.tools

import com.hmx.webide.ai.models.ToolCall
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

  fun dispatch(call: ToolCall): ToolResult = runCatching {
    val args = JSONObject(call.arguments.ifBlank { "{}" })
    when (call.name) {
      "read_file" -> read(args.getString("path"))
      "write_file" -> write(args.getString("path"), args.optString("content", ""))
      "list_files" -> list(args.optString("path", "."))
      "delete_file" -> delete(args.getString("path"))
      else -> ToolResult.error("UNKNOWN_TOOL", "unknown tool '${call.name}'")
    }
  }.getOrElse { ToolResult.error("INTERNAL", "Error: ${it.message ?: it.javaClass.simpleName}") }

  private fun read(rel: String): ToolResult {
    val file = resolve(rel) ?: return ToolResult.error("INVALID_PATH", "invalid or out-of-project path '$rel'")
    if (!file.isFile) return ToolResult.error("FILE_NOT_FOUND", "file not found: $rel")
    return runCatching { ToolResult.ok(file.readText()) }
      .getOrDefault(ToolResult.error("PERMISSION", "could not read $rel"))
  }

  private fun write(rel: String, content: String): ToolResult {
    val file = resolve(rel) ?: return ToolResult.error("INVALID_PATH", "invalid or out-of-project path '$rel'")
    runCatching { file.parentFile?.mkdirs() }
      .getOrElse { return ToolResult.error("PERMISSION", "cannot create parent dirs for $rel") }
    runCatching { file.writeText(content) }
      .getOrElse { return ToolResult.error("PERMISSION", "could not write $rel") }
    changedFiles++
    return ToolResult.ok("Wrote ${content.length} chars to $rel")
  }

  private fun list(rel: String): ToolResult {
    val dir = resolve(rel) ?: return ToolResult.error("INVALID_PATH", "invalid or out-of-project path '$rel'")
    if (!dir.isDirectory) return ToolResult.error("FILE_NOT_FOUND", "not a directory: $rel")
    val entries = dir.listFiles() ?: return ToolResult.error("PERMISSION", "could not list $rel")
    val text = entries.sortedBy { it.name }.joinToString("\n") {
      "${if (it.isDirectory) "[dir] " else ""}${it.name}"
    }
    return ToolResult.ok(text)
  }

  private fun delete(rel: String): ToolResult {
    val file = resolve(rel) ?: return ToolResult.error("INVALID_PATH", "invalid or out-of-project path '$rel'")
    if (!file.isFile) return ToolResult.error("FILE_NOT_FOUND", "not a file (refusing to delete directory): $rel")
    runCatching { file.delete() }
      .getOrElse { return ToolResult.error("PERMISSION", "could not delete $rel") }
    changedFiles++
    return ToolResult.ok("Deleted $rel")
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
