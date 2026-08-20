package com.hmx.webide.ai.context

import java.io.File

data class ScanResult(
  val htmlFiles: List<File> = emptyList(),
  val cssFiles: List<File> = emptyList(),
  val jsFiles: List<File> = emptyList(),
  val jsonFiles: List<File> = emptyList(),
  val mdFiles: List<File> = emptyList(),
  val packageJsonFile: File? = null,
  val allSourceFiles: List<File> = emptyList(),
  val fileInfos: List<ProjectFileInfo> = emptyList(),
  val totalFiles: Int = 0,
)

object ProjectScanner {

  val WEB_EXTENSIONS = setOf("html", "htm", "css", "js", "mjs", "cjs", "json", "md", "txt", "yml", "yaml", "ts", "tsx", "jsx", "vue", "svg")

  private val SKIP_DIRS = setOf("build", ".git", ".gradle", "bin", "obj", "node_modules", "cmake-build-debug", ".idea", "dist")

  fun scan(root: File, onProgress: ((String) -> Unit)? = null): ScanResult {
    val htmlFiles = mutableListOf<File>()
    val cssFiles = mutableListOf<File>()
    val jsFiles = mutableListOf<File>()
    val jsonFiles = mutableListOf<File>()
    val mdFiles = mutableListOf<File>()
    val allSourceFiles = mutableListOf<File>()
    val fileInfos = mutableListOf<ProjectFileInfo>()
    var packageJsonFile: File? = null

    val allFiles = mutableListOf<File>()
    walkProject(root) { allFiles.add(it) }
    val total = allFiles.size
    var count = 0

    for (file in allFiles) {
      count++
      if (total > 100 && count % (total / 10) == 0) {
        onProgress?.invoke("Scanning... ${(count * 100 / total)}%")
      }
      val ext = file.extension.lowercase()
      when (ext) {
        "html", "htm" -> htmlFiles.add(file)
        "css" -> cssFiles.add(file)
        "js", "mjs", "cjs" -> jsFiles.add(file)
        "json" -> {
          jsonFiles.add(file)
          if (file.name == "package.json" && packageJsonFile == null) packageJsonFile = file
        }
        "md", "markdown" -> mdFiles.add(file)
      }
      if (ext in WEB_EXTENSIONS) {
        allSourceFiles.add(file)
        if (ext in PARSEABLE_EXTENSIONS) {
          fileInfos.add(buildFileInfo(file, root))
        }
      }
    }

    onProgress?.invoke("✓ Indexed ${allSourceFiles.size} files")

    return ScanResult(
      htmlFiles = htmlFiles,
      cssFiles = cssFiles,
      jsFiles = jsFiles,
      jsonFiles = jsonFiles,
      mdFiles = mdFiles,
      packageJsonFile = packageJsonFile,
      allSourceFiles = allSourceFiles,
      fileInfos = fileInfos,
      totalFiles = total,
    )
  }

  private fun buildFileInfo(file: File, root: File): ProjectFileInfo {
    val content = runCatching { file.readText() }.getOrNull() ?: return ProjectFileInfo(
      path = file.absolutePath,
      relativePath = file.relativeTo(root).path,
    )
    val imports = when (file.extension.lowercase()) {
      "js", "mjs", "cjs", "ts", "tsx", "jsx" ->
        Regex("""(?:import|export)\s+.*?from\s+['"]([^'"]+)['"]""")
          .findAll(content).map { it.groupValues[1] }.toList()
      "html" ->
        Regex("""<script[^>]+src\s*=\s*['"]([^'"]+)['"]""")
          .findAll(content).map { it.groupValues[1] }.toList()
      "css" ->
        Regex("""@import\s+['"]([^'"]+)['"]""")
          .findAll(content).map { it.groupValues[1] }.toList()
      else -> emptyList()
    }
    val names = Regex("""\b(?:function|class|const|let|var|async function)\s+([A-Za-z_$][\w$]*)""")
      .findAll(content).map { it.groupValues[1] }.toList()
    return ProjectFileInfo(
      path = file.absolutePath,
      relativePath = file.relativeTo(root).path,
      imports = imports,
      classes = names,
    )
  }

  private fun walkProject(root: File, action: (File) -> Unit) {
    val queue = ArrayDeque<File>().apply { add(root) }
    while (queue.isNotEmpty()) {
      val dir = queue.removeFirst()
      val children = dir.listFiles() ?: continue
      for (child in children) {
        if (child.isDirectory) {
          if (child.name !in SKIP_DIRS) queue.add(child)
        } else {
          action(child)
        }
      }
    }
  }

  private val PARSEABLE_EXTENSIONS = setOf("html", "htm", "css", "js", "mjs", "cjs", "json", "md", "ts", "tsx", "jsx")
}