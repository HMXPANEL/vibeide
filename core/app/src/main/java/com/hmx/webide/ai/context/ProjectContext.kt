package com.hmx.webide.ai.context

data class ProjectContext(
  val projectType: WebProjectType = WebProjectType.UNKNOWN_WEB_PROJECT,
  val languages: Set<WebLanguage> = emptySet(),
  val entryFile: String? = null,
  val hasPackageJson: Boolean = false,
  val dependencies: Set<String> = emptySet(),
  val scripts: List<String> = emptyList(),
  val fileCount: Int = 0,
  val dirs: List<String> = emptyList(),
  val lastAnalyzed: Long = System.currentTimeMillis(),
  val projectDir: String = "",
)