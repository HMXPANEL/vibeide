package com.hmx.webide.ai.context

import java.io.File

object ProjectAnalyzer {

  fun analyze(root: File, scan: ScanResult): ProjectIndex {
    val projectType = WebProjectDetector.detect(root)
    val languages = detectLanguages(scan)
    val dependencies = detectDependencies(scan)
    val scripts = detectScripts(scan)
    val entryFile = if (File(root, "index.html").isFile) "index.html" else null
    val dirs = detectTopLevelDirs(root)

    val context = ProjectContext(
      projectType = projectType,
      languages = languages,
      entryFile = entryFile,
      hasPackageJson = scan.packageJsonFile != null,
      dependencies = dependencies,
      scripts = scripts,
      fileCount = scan.allSourceFiles.size,
      dirs = dirs,
      projectDir = root.absolutePath,
    )

    return ProjectIndex(
      context = context,
      files = scan.fileInfos,
      totalSourceFiles = scan.allSourceFiles.size,
      totalFiles = scan.totalFiles,
    )
  }

  private fun detectLanguages(scan: ScanResult): Set<WebLanguage> {
    val languages = mutableSetOf<WebLanguage>()
    if (scan.htmlFiles.isNotEmpty()) languages.add(WebLanguage.HTML)
    if (scan.cssFiles.isNotEmpty()) languages.add(WebLanguage.CSS)
    if (scan.jsFiles.isNotEmpty()) languages.add(WebLanguage.JAVASCRIPT)
    if (scan.jsonFiles.isNotEmpty()) languages.add(WebLanguage.JSON)
    if (scan.mdFiles.isNotEmpty()) languages.add(WebLanguage.MARKDOWN)
    return languages
  }

  private fun detectDependencies(scan: ScanResult): Set<String> {
    val pkg = scan.packageJsonFile ?: return emptySet()
    val deps = WebProjectDetector.readPackageJson(pkg.parentFile) ?: return emptySet()
    return deps.keys
  }

  private fun detectScripts(scan: ScanResult): List<String> {
    val pkg = scan.packageJsonFile ?: return emptyList()
    val text = runCatching { pkg.readText() }.getOrNull() ?: return emptyList()
    val regex = Regex("\"scripts\"\\s*:\\s*\\{([^}]*)}")
    val body = regex.find(text)?.groupValues?.getOrNull(1) ?: return emptyList()
    return Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
      .map { "${it.groupValues[1]}: ${it.groupValues[2]}" }
      .toList()
  }

  private fun detectTopLevelDirs(root: File): List<String> {
    val children = root.listFiles() ?: return emptyList()
    return children.filter { it.isDirectory && it.name !in setOf(".git", "node_modules") }
      .map { it.name }
      .sorted()
  }
}