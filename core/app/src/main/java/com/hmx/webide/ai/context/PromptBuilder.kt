package com.hmx.webide.ai.context

import java.io.File

object PromptBuilder {

  fun build(index: ProjectIndex, currentFile: String? = null): String {
    val ctx = index.context
    val sb = StringBuilder()

    sb.appendLine("You are an AI coding assistant for a web project.")
    sb.appendLine()

    sb.appendLine("=== PROJECT STATUS ===")
    sb.appendLine("Project: ${File(ctx.projectDir).name}")
    sb.appendLine("Type: ${ctx.projectType.display}")
    sb.appendLine("Languages: ${ctx.languages.joinToString(", ") { it.display }.ifEmpty { "Unknown" }}")
    ctx.entryFile?.let { sb.appendLine("Entry: $it") }
    if (ctx.hasPackageJson) sb.appendLine("Has package.json: yes")
    if (ctx.dependencies.isNotEmpty()) {
      sb.appendLine("Dependencies: ${ctx.dependencies.sorted().joinToString(", ")}")
    }
    if (ctx.scripts.isNotEmpty()) {
      sb.appendLine("Scripts: ${ctx.scripts.joinToString(", ")}")
    }
    if (ctx.dirs.isNotEmpty()) {
      sb.appendLine("Directories: ${ctx.dirs.joinToString(", ")}")
    }
    sb.appendLine("Files: ${index.totalSourceFiles} source files")
    if (currentFile != null) {
      sb.appendLine("Current File: $currentFile")
    }
    sb.appendLine()

    sb.appendLine("=== RULES ===")
    sb.appendLine("1. Always match the existing project's languages (${ctx.languages.joinToString(", ") { it.display }.ifEmpty { "Unknown" }}).")
    sb.appendLine("2. Prefer plain HTML/CSS/JS unless the project already uses a framework (${ctx.projectType.display}).")
    sb.appendLine("3. Reference actual files and symbols in this project.")
    sb.appendLine("4. When generating a new file, put it in a sensible location for this project type.")
    sb.appendLine("5. Do NOT generate generic templates. Every response must be tailored to this project.")
    sb.appendLine("6. Never invent files or paths that do not exist.")
    sb.appendLine()

    sb.appendLine("=== FILE MODIFICATION ===")
    sb.appendLine("To create or modify a file:")
    sb.appendLine("[[WRITE:relative/path/file.html]]")
    sb.appendLine("<full file content>")
    sb.appendLine("[[END]]")
    sb.appendLine()
    sb.appendLine("When the user says 'analyze project', provide a full analysis.")
    sb.appendLine("Otherwise just answer conversationally.")

    return sb.toString()
  }

  /**
   * Builds the chat startup message from an already-computed [ProjectContextSummary].
   *
   * Performs no IO. Fields that are unavailable are rendered as `Unknown` rather than guessed.
   */
  fun buildStartupMessage(
    summary: ProjectContextSummary,
    currentFile: String? = null,
  ): String {
    val sb = StringBuilder()

    when (summary.state) {
      IndexingState.READY, IndexingState.UPDATING -> sb.appendLine("Project Ready ✅")
      IndexingState.INDEXING -> sb.appendLine("Scanning project…")
      IndexingState.UNAVAILABLE -> sb.appendLine("Project context unavailable")
    }
    sb.appendLine()

    sb.appendLine("Project: ${summary.projectName}")
    summary.projectType?.let { sb.appendLine("Type: $it") }
    summary.languages?.let { sb.appendLine("Languages: $it") }
    summary.entryFile?.let { sb.appendLine("Entry: $it") }
    if (summary.dependencyCount > 0) sb.appendLine("Dependencies: ${summary.dependencyCount}")
    if (summary.totalFiles > 0) sb.appendLine("Files: ${summary.totalFiles}")
    if (summary.lastIndexedAt > 0L) {
      sb.appendLine("Last Indexed: ${formatRelativeTime(summary.lastIndexedAt)}")
    }
    currentFile?.let { sb.appendLine("Current File: $it") }

    sb.appendLine()
    sb.appendLine("Capabilities")
    sb.appendLine("• Explain code • Generate code • Fix errors • Find bugs")
    sb.appendLine("• Refactor • Search project • Analyze architecture • Explain APIs")

    return sb.toString().trimEnd()
  }

  /** Formats a timestamp as a short relative string, e.g. `2 sec ago`. */
  fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Unknown"
    val deltaMs = System.currentTimeMillis() - timestampMs
    if (deltaMs < 0L) return "just now"
    val seconds = deltaMs / 1000L
    return when {
      seconds < 5L -> "just now"
      seconds < 60L -> "$seconds sec ago"
      seconds < 3_600L -> "${seconds / 60L} min ago"
      seconds < 86_400L -> "${seconds / 3_600L} hr ago"
      else -> "${seconds / 86_400L} day(s) ago"
    }
  }

  fun buildAnalysis(index: ProjectIndex): String {
    val ctx = index.context
    val sb = StringBuilder()

    sb.appendLine("=== PROJECT ANALYSIS ===")
    sb.appendLine()
    sb.appendLine("Project: ${File(ctx.projectDir).name}")
    sb.appendLine("Type: ${ctx.projectType.display}")
    sb.appendLine("Languages: ${ctx.languages.joinToString(", ") { it.display }.ifEmpty { "N/A" }}")
    sb.appendLine("Entry: ${ctx.entryFile ?: "N/A"}")
    sb.appendLine("Source Files: ${index.totalSourceFiles}")
    sb.appendLine()

    if (ctx.dependencies.isNotEmpty()) {
      sb.appendLine("=== DEPENDENCIES ===")
      ctx.dependencies.sorted().forEach { sb.appendLine("- $it") }
      sb.appendLine()
    }

    if (ctx.scripts.isNotEmpty()) {
      sb.appendLine("=== SCRIPTS ===")
      ctx.scripts.forEach { sb.appendLine("- $it") }
      sb.appendLine()
    }

    if (index.files.isNotEmpty()) {
      sb.appendLine("=== FILE LAYOUT ===")
      index.files.map { it.relativePath }.sorted().take(50).forEach { sb.appendLine("- $it") }
      sb.appendLine()
    }

    sb.appendLine("To analyze code quality or find bugs, ask about specific files.")
    return sb.toString()
  }

  private val WebProjectType.display: String get() = when (this) {
    WebProjectType.STATIC_HTML -> "Static HTML"
    WebProjectType.NODE_PROJECT -> "Node.js"
    WebProjectType.VITE_PROJECT -> "Vite"
    WebProjectType.REACT_PROJECT -> "React"
    WebProjectType.UNKNOWN_WEB_PROJECT -> "Web"
  }

  private val WebLanguage.display: String get() = when (this) {
    WebLanguage.HTML -> "HTML"
    WebLanguage.CSS -> "CSS"
    WebLanguage.JAVASCRIPT -> "JavaScript"
    WebLanguage.JSON -> "JSON"
    WebLanguage.MARKDOWN -> "Markdown"
    WebLanguage.TYPESCRIPT -> "TypeScript"
    WebLanguage.UNKNOWN -> "Unknown"
  }
}