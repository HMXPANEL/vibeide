package com.hmx.webide.ai.context

import java.io.File

enum class WebProjectType {
  STATIC_HTML,
  NODE_PROJECT,
  VITE_PROJECT,
  REACT_PROJECT,
  UNKNOWN_WEB_PROJECT,
}

enum class WebLanguage {
  HTML,
  CSS,
  JAVASCRIPT,
  JSON,
  MARKDOWN,
  TYPESCRIPT,
  UNKNOWN,
}

/**
 * Detects the kind of web project rooted at the given directory.
 *
 * A project is considered a web project when it contains at least an `index.html`.
 * Project type is refined from `package.json`:
 * - `vite` dependency/devDependency -> [WebProjectType.VITE_PROJECT]
 * - `react`/`react-dom` -> [WebProjectType.REACT_PROJECT]
 * - any scripts/engines/dependencies -> [WebProjectType.NODE_PROJECT]
 * - otherwise -> [WebProjectType.STATIC_HTML]
 */
object WebProjectDetector {

  private val VITE_MARKERS = setOf("vite", "@vitejs/plugin-react")
  private val REACT_MARKERS = setOf("react", "react-dom", "@types/react")

  fun detect(root: File): WebProjectType {
    if (!root.isDirectory) return WebProjectType.UNKNOWN_WEB_PROJECT
    val indexHtml = File(root, "index.html").exists()
    if (!indexHtml) return WebProjectType.UNKNOWN_WEB_PROJECT

    val packageJson = File(root, "package.json")
    if (packageJson.isFile) {
      val deps = readPackageJson(root)
      if (deps != null && VITE_MARKERS.any { it in deps }) return WebProjectType.VITE_PROJECT
      if (deps != null && REACT_MARKERS.any { it in deps }) return WebProjectType.REACT_PROJECT
      if (deps != null && deps.isNotEmpty()) return WebProjectType.NODE_PROJECT
      val text = runCatching { packageJson.readText() }.getOrNull()
      if (text != null && Regex("\"scripts\"\\s*:").containsMatchIn(text)) return WebProjectType.NODE_PROJECT
    }
    return WebProjectType.STATIC_HTML
  }

  /** Returns dependency names (name -> version) from `package.json`, or null if absent/invalid. */
  fun readPackageJson(root: File): Map<String, String>? {
    val file = File(root, "package.json")
    if (!file.isFile) return null
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    val deps = mutableMapOf<String, String>()
    for (section in listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
      val regex = Regex("\"$section\"\\s*:\\s*\\{([^}]*)}")
      val body = regex.find(text)?.groupValues?.getOrNull(1) ?: continue
      Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach {
        deps[it.groupValues[1]] = it.groupValues[2]
      }
    }
    return deps
  }
}