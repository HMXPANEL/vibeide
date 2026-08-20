package com.hmx.webide.ai.pipeline

import com.hmx.webide.ai.context.ContextCache
import com.hmx.webide.ai.context.ContextManager
import com.hmx.webide.ai.context.EditorContext
import com.hmx.webide.ai.context.ProjectIndex
import com.hmx.webide.ai.context.PromptBuilder
import com.hmx.webide.ai.memory.MemoryManager
import com.hmx.webide.ai.memory.MemorySearchResult
import com.hmx.webide.ai.memory.MemoryService
import com.hmx.webide.ai.memory.MemoryService.withProject
import com.hmx.webide.ai.memory.MemoryService.withProjectKnowledge
import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.Role
import java.io.File

class ContextPipeline(
  private val projectDir: File,
) {

  private val index: ProjectIndex by lazy {
    ContextCache.getOrAnalyze(projectDir.absolutePath)
  }

  private val memoryManager: MemoryManager by lazy {
    MemoryService.withProject(projectDir)
  }

  fun buildSystemPrompt(currentFile: String? = null): String {
    return PromptBuilder.build(index, currentFile)
  }

  fun buildSystemPromptWithMemory(currentFile: String? = null): String {
    val base = buildSystemPrompt(currentFile)
    val memoryHint = buildMemoryHint()
    if (memoryHint.isEmpty()) return base
    return "$base\n\n$memoryHint"
  }

  fun buildFullContext(currentFile: String? = null): PipelineContext {
    return PipelineContext(
      projectIndex = index,
      editorContext = ContextManager.collectContext(),
      memorySummary = buildMemorySummary(),
      relevantMemory = emptyList(),
      systemPrompt = buildSystemPromptWithMemory(currentFile),
    )
  }

  fun processQuery(query: String): List<ChatMessage> {
    val needs = QueryAnalyzer.analyze(query)
    val ctx = ContextManager.collectContext()
    val contextBlocks = mutableListOf<String>()

    if (ContextNeed.CURRENT_FILE in needs)
      contextBlocks.add(collectCurrentFile(ctx))
    if (ContextNeed.PROJECT_STRUCTURE in needs)
      contextBlocks.add(collectProjectStructure())
    if (ContextNeed.PROJECT_MEMORY in needs)
      contextBlocks.add(collectProjectMemory(query))

    val systemPrompt = buildSystemPrompt(ctx.currentFile)
    val collected = contextBlocks.filter { it.isNotBlank() }.joinToString("\n\n")
    val fullPrompt = if (collected.isNotBlank()) "$systemPrompt\n\nContext:\n$collected" else systemPrompt

    return listOf(
      ChatMessage(Role.system, fullPrompt),
      ChatMessage(Role.user, query),
    )
  }

  private fun collectCurrentFile(ctx: EditorContext): String {
    val file = ctx.currentFile ?: return ""
    val selected = ctx.selectedCode
    if (!selected.isNullOrBlank())
      return "Selected code in $file:\n```\n$selected\n```"
    val content = try { File(file).readText() } catch (_: Exception) { null } ?: return ""
    return "File $file:\n```\n$content\n```"
  }

  private fun collectProjectStructure(): String {
    val pc = index.context
    val sb = StringBuilder("Project: ${pc.projectType.name.lowercase().replace('_', ' ')} | ${pc.languages.joinToString(", ") { it.name }}")
    pc.entryFile?.let { sb.append("\nEntry: $it") }
    if (pc.hasPackageJson) sb.append("\nHas package.json: yes")
    if (pc.dependencies.isNotEmpty()) sb.append("\nDependencies: ${pc.dependencies.sorted().joinToString(", ")}")
    if (pc.scripts.isNotEmpty()) sb.append("\nScripts: ${pc.scripts.joinToString(", ")}")
    if (pc.dirs.isNotEmpty()) sb.append("\nDirectories: ${pc.dirs.joinToString(", ")}")
    sb.append("\nFiles: ${index.totalSourceFiles} source files, ${index.totalFiles} total")
    return sb.toString()
  }

  private fun collectProjectMemory(query: String): String {
    val km = withProjectKnowledge(projectDir)
    val entries = km.search(query).take(10)
    if (entries.isEmpty()) return ""
    return entries.joinToString("\n") { e ->
      "[${e.category.label}] ${e.key}: ${e.value.take(200)}"
    }
  }

  fun findRelevantMemory(query: String): List<MemorySearchResult> {
    return memoryManager.search(query)
  }

  private fun buildMemoryHint(): String {
    val summaries = withProject(projectDir).getSummaries()
    if (summaries.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("=== PROJECT MEMORY ===")
    for (s in summaries.take(5)) {
      val content = s.content.take(200)
      sb.append("- ${s.title ?: "Summary"}: $content")
    }
    return sb.toString()
  }

  private fun buildMemorySummary(): String {
    val mm = withProject(projectDir)
    val stats = mm.getMemoryStats()
    val decisions = mm.getDecisions("done").take(10)
    val todos = mm.getTodos().filter { !it.done }.take(10)

    val sb = StringBuilder()
    sb.append("Project has ${stats.totalEntries} memory entries.")
    if (decisions.isNotEmpty()) {
      sb.append(" Recent decisions:")
      for (d in decisions) {
        sb.append(" - ${d.title}")
      }
    }
    if (todos.isNotEmpty()) {
      sb.append(" Open TODOs:")
      for (t in todos) {
        sb.append(" - ${t.title}")
      }
    }
    return sb.toString()
  }

  fun cleanupMemory() {
    withProject(projectDir).let { mm ->
      mm.cleanupOldConversations()
      mm.cleanupExpiredCache()
      mm.compactDatabase()
    }
  }
}

data class PipelineContext(
  val projectIndex: ProjectIndex,
  val editorContext: EditorContext,
  val memorySummary: String,
  val relevantMemory: List<MemorySearchResult>?,
  val systemPrompt: String,
)
