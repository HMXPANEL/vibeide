package com.hmx.webide.ai.tools

import com.hmx.webide.ai.models.Tool
import com.hmx.webide.ai.models.ToolParameter
import org.json.JSONObject

/**
 * Authoritative registry of AI tools. The provider schema ([tools]) and the execution
 * dispatch (via [ProjectFileOps]) both derive from here, so adding a future tool
 * (terminal, preview, search, git, mcp) is a single-entry change and the write policy
 * in [FileWriteGate] is applied uniformly.
 */
object ToolRegistry {

  private val definitions = listOf(
    ToolDefinition(
      name = "read_file",
      description = "Read a project file by relative path to inspect existing code.",
      parameters = listOf(ToolParameter("path", "string", "Relative path, e.g. src/main.js", true)),
      readOnly = true,
      dangerous = false,
    ),
    ToolDefinition(
      name = "write_file",
      description = "Create or overwrite a project file with the given content. Use this to build or edit the project.",
      parameters = listOf(
        ToolParameter("path", "string", "Relative path, e.g. index.html or src/style.css", true),
        ToolParameter("content", "string", "Full new file content", true),
      ),
      readOnly = false,
      dangerous = false,
    ),
    ToolDefinition(
      name = "list_files",
      description = "List files in a project directory to understand the layout.",
      parameters = listOf(ToolParameter("path", "string", "Relative directory path, defaults to project root", false)),
      readOnly = true,
      dangerous = false,
    ),
    ToolDefinition(
      name = "delete_file",
      description = "Delete a project file when explicitly required.",
      parameters = listOf(ToolParameter("path", "string", "Relative path of the file to delete", true)),
      readOnly = false,
      dangerous = true,
    ),
    ToolDefinition(
      name = "run_command",
      description = "Run a shell command in the project directory (e.g. npm install, npm run dev, node -v). Use only when file tools cannot accomplish the task.",
      parameters = listOf(
        ToolParameter("command", "string", "The shell command to execute, e.g. 'npm install' or 'npm run build'", true),
      ),
      readOnly = false,
      dangerous = true,
      timeoutMs = 120_000,
    ),
  )

  private val byName = definitions.associateBy { it.name }

  /** Tool schema sent to the provider. */
  fun tools(): List<Tool> = definitions.map { Tool(it.name, it.description, it.parameters) }

  fun get(name: String): ToolDefinition? = byName[name]

  /** Validates required arguments. Returns an error [ToolResult] or null when valid. */
  fun validateArguments(def: ToolDefinition, args: JSONObject): ToolResult? {
    for (p in def.parameters) {
      if (p.required && (!args.has(p.name) || args.optString(p.name, "").isBlank())) {
        return ToolResult.error("INVALID_ARGUMENT", "missing required argument '${p.name}' for tool '${def.name}'")
      }
    }
    return null
  }
}

data class ToolDefinition(
  val name: String,
  val description: String,
  val parameters: List<ToolParameter>,
  val readOnly: Boolean,
  val dangerous: Boolean,
  val timeoutMs: Long = 30_000,
)
