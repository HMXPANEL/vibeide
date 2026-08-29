package com.hmx.webide.ai.tools

import com.hmx.webide.ai.models.ToolCall
import org.json.JSONObject
import java.io.File

/**
 * Project-scoped file operations the AI can invoke through tools.
 *
 * Every path is resolved against [root] (the currently opened project) and is rejected
 * unless it stays strictly inside it (no absolute paths, no `..` traversal). The
 * application, not the model's free-form text, performs the actual filesystem write — this
 * is what makes file editing reliable. Tool metadata lives in [ToolRegistry] and the write
 * policy in [FileWriteGate], so both the provider schema and the dispatch stay in sync.
 */
class ProjectFileOps(private val root: File) {

  /** Number of successful write/delete operations performed this session. */
  var changedFiles = 0
    private set

  private val gate = FileWriteGate(root)

  fun dispatch(call: ToolCall): ToolResult {
    val def = ToolRegistry.get(call.name)
    if (def == null) return ToolResult.error("UNKNOWN_TOOL", "unknown tool '${call.name}'")
    val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }
      .getOrElse { return ToolResult.error("INVALID_ARGUMENT", "arguments for '${call.name}' are not valid JSON") }
    ToolRegistry.validateArguments(def, args)?.let { return it }
    return runCatching {
      when (def.name) {
        "read_file" -> read(args.getString("path"))
        "write_file" -> write(args.getString("path"), args.optString("content", ""))
        "list_files" -> list(args.optString("path", "."))
        "delete_file" -> delete(args.getString("path"))
        else -> ToolResult.error("UNKNOWN_TOOL", "unknown tool '${call.name}'")
      }
    }.getOrElse { ToolResult.error("INTERNAL", "Error: ${it.message ?: it.javaClass.simpleName}") }
  }

  private fun read(rel: String): ToolResult {
    val file = resolveInProject(root, rel) ?: return ToolResult.error("INVALID_PATH", "invalid or out-of-project path '$rel'")
    if (!file.isFile) return ToolResult.error("FILE_NOT_FOUND", "file not found: $rel")
    return runCatching { ToolResult.ok(file.readText()) }
      .getOrDefault(ToolResult.error("PERMISSION", "could not read $rel"))
  }

  private fun write(rel: String, content: String): ToolResult {
    return when (val d = gate.check(rel)) {
      is FileWriteGate.Decision.Deny -> ToolResult.error(d.code, d.message)
      is FileWriteGate.Decision.Allow -> {
        val file = d.file
        runCatching { file.parentFile?.mkdirs() }
          .getOrElse { return ToolResult.error("PERMISSION", "cannot create parent dirs for $rel") }
        runCatching { file.writeText(content) }
          .getOrElse { return ToolResult.error("PERMISSION", "could not write $rel") }
        changedFiles++
        ToolResult.ok("Wrote ${content.length} chars to $rel")
      }
    }
  }

  private fun list(rel: String): ToolResult {
    val dir = resolveInProject(root, rel) ?: return ToolResult.error("INVALID_PATH", "invalid or out-of-project path '$rel'")
    if (!dir.isDirectory) return ToolResult.error("FILE_NOT_FOUND", "not a directory: $rel")
    val entries = dir.listFiles() ?: return ToolResult.error("PERMISSION", "could not list $rel")
    val text = entries.sortedBy { it.name }.joinToString("\n") {
      "${if (it.isDirectory) "[dir] " else ""}${it.name}"
    }
    return ToolResult.ok(text)
  }

  private fun delete(rel: String): ToolResult {
    return when (val d = gate.check(rel)) {
      is FileWriteGate.Decision.Deny -> ToolResult.error(d.code, d.message)
      is FileWriteGate.Decision.Allow -> {
        val file = d.file
        if (!file.isFile) return ToolResult.error("FILE_NOT_FOUND", "not a file (refusing to delete directory): $rel")
        runCatching { file.delete() }
          .getOrElse { return ToolResult.error("PERMISSION", "could not delete $rel") }
        changedFiles++
        ToolResult.ok("Deleted $rel")
      }
    }
  }

  /**
   * Legacy `[[WRITE:]]` fallback routed through the same gate as tool writes, so the
   * write policy can never be bypassed. The caller counts successes from [ToolResult.isError].
   */
  fun applyEdit(rel: String, content: String): ToolResult {
    return when (val d = gate.check(rel)) {
      is FileWriteGate.Decision.Deny -> ToolResult.error(d.code, d.message)
      is FileWriteGate.Decision.Allow -> {
        val file = d.file
        runCatching { file.parentFile?.mkdirs() }
          .getOrElse { return ToolResult.error("PERMISSION", "cannot create parent dirs for $rel") }
        runCatching { file.writeText(content) }
          .getOrElse { return ToolResult.error("PERMISSION", "could not write $rel") }
        ToolResult.ok("Wrote ${content.length} chars to $rel")
      }
    }
  }
}
