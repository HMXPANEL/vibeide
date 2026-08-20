package com.hmx.webide.ai.context

import com.hmx.webide.knowledge.KnowledgeEngineImpl
import java.io.File

object ContextCache {

  private data class Entry(
    val index: ProjectIndex,
    val trackedFiles: Map<String, Long>,
  )

  private val cache = mutableMapOf<String, Entry>()

  private var _getOrAnalyzeCallCount: Int = 0
  private var _bypassCount: Int = 0
  private var _scanCount: Int = 0

  val getOrAnalyzeCallCount get() = _getOrAnalyzeCallCount
  val bypassCount get() = _bypassCount
  val scanCount get() = _scanCount

  @Synchronized
  fun get(projectDir: String): ProjectIndex? {
    val entry = cache[projectDir] ?: return null
    if (isStale(projectDir, entry)) {
      cache.remove(projectDir)
      return null
    }
    return entry.index
  }

  fun getContext(projectDir: String): ProjectContext? = get(projectDir)?.context

  /**
   * Returns a display summary built from **already available** index data, without triggering a
   * scan.
   *
   * Lookup order (cheapest first):
   * 1. this cache,
   * 2. [KnowledgeEngineImpl.unifiedIndex] (populated when a project was opened in the IDE).
   *
   * Returns a summary with [IndexingState.UNAVAILABLE] when neither source has data, so callers
   * can render "Unknown" instead of blocking on a scan.
   */
  @Synchronized
  fun getSummary(projectDir: String): ProjectContextSummary {
    get(projectDir)?.let { return ProjectContextSummary.from(it, IndexingState.READY) }

    KnowledgeEngineImpl.unifiedIndex?.project?.let { unified ->
      return ProjectContextSummary.from(unified, IndexingState.READY)
    }

    return ProjectContextSummary.unavailable(projectDir)
  }

  @Synchronized
  fun set(projectDir: String, index: ProjectIndex) {
    val root = File(projectDir)
    cache[projectDir] = Entry(
      index = index,
      trackedFiles = mapOf(
        "index_html" to getModStamp(File(root, "index.html")),
        "package_json" to getModStamp(File(root, "package.json")),
        "vite_config" to getModStamp(File(root, "vite.config.js")),
      ),
    )
  }

  @Synchronized
  fun invalidate(projectDir: String) {
    cache.remove(projectDir)
  }

  @Synchronized
  fun getOrAnalyze(projectDir: String, onProgress: ((String) -> Unit)? = null): ProjectIndex {
    _getOrAnalyzeCallCount++
    val cached = get(projectDir)
    if (cached != null) return cached

    val root = File(projectDir)
    if (!root.isDirectory) return ProjectIndex(
      context = ProjectContext(projectDir = projectDir)
    )

    val unified = KnowledgeEngineImpl.unifiedIndex?.project
    if (unified != null) {
      _bypassCount++
      onProgress?.invoke("✓ Using cached project index")
      set(projectDir, unified)
      return unified
    }

    _scanCount++
    @Suppress("DEPRECATION")
    val scanResult = deprecatedScan(root, onProgress)
    val index = ProjectAnalyzer.analyze(root, scanResult)
    set(projectDir, index)
    onProgress?.invoke("✓ Project indexing completed")
    return index
  }

  /**
   * @deprecated Use [KnowledgeEngineImpl.unifiedIndex] instead. This full project scan is
   *   bypassed when the knowledge engine has already indexed the project. Kept for backward
   *   compatibility and as a fallback when no project has been opened through [KnowledgeEngineImpl].
   */
  @Deprecated("Use KnowledgeEngineImpl.unifiedIndex instead; this path is a fallback only.")
  private fun deprecatedScan(root: File, onProgress: ((String) -> Unit)?): ScanResult {
    onProgress?.invoke("Scanning project...")
    val scanResult = ProjectScanner.scan(root, onProgress)
    onProgress?.invoke("Analyzing project structure...")
    return scanResult
  }

  private fun isStale(projectDir: String, entry: Entry): Boolean {
    for ((key, stamp) in entry.trackedFiles) {
      val file = when (key) {
        "index_html" -> File(projectDir, "index.html")
        "package_json" -> File(projectDir, "package.json")
        "vite_config" -> File(projectDir, "vite.config.js")
        else -> continue
      }
      if (file.exists()) {
        val currentStamp = file.lastModified()
        if (currentStamp != stamp) return true
      }
    }
    return false
  }

  private fun getModStamp(file: File): Long {
    return if (file.exists()) file.lastModified() else -1L
  }
}
