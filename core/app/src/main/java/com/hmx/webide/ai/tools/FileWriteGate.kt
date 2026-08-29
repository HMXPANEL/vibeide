package com.hmx.webide.ai.tools

import java.io.File

/**
 * Central write policy for AI-driven file operations.
 *
 * Wraps the project path-safety check ([resolveInProject]) and adds a deny-list of
 * files the AI must never overwrite or delete (package manifests, lockfiles, env files,
 * build configs, VCS metadata). There is no UI confirmation step yet; a protected write
 * is simply refused with a typed [ToolResult] so the model can adapt.
 */
class FileWriteGate(private val root: File) {

  sealed interface Decision {
    data class Allow(val file: File) : Decision
    data class Deny(val code: String, val message: String) : Decision
  }

  /** Validate a write/delete target. Out-of-project paths and protected files are denied. */
  fun check(rel: String): Decision {
    val file = resolveInProject(root, rel)
    if (file == null) {
      return Decision.Deny("INVALID_PATH", "invalid or out-of-project path '$rel'")
    }
    if (isProtected(rel)) {
      return Decision.Deny("DENIED", "write to '$rel' is blocked by the project write policy (protected file)")
    }
    return Decision.Allow(file)
  }

  private fun isProtected(rel: String): Boolean {
    val cleaned = rel.trim().removePrefix("/").removePrefix("./")
    val base = cleaned.substringAfterLast('/')
    return PROTECTED.any { it.matches(cleaned) || it.matches(base) }
  }

  companion object {
    // ponytail: starter deny-list; expand when a real config file is being clobbered.
    private val PROTECTED = listOf(
      Regex("""^(package\.json|package-lock\.json|yarn\.lock|pnpm-lock\.yaml|npm-shrinkwrap\.json|bun\.lockb)$"""),
      Regex("""^\.env.*$"""),
      Regex("""^(tsconfig|vite\.config|webpack\.config|next\.config|babel\.config|tailwind\.config|postcss\.config|rollup\.config)(\..*)?$"""),
      Regex("""^\.(eslintrc|prettierrc|npmrc|yarnrc|editorconfig|gitignore)$"""),
      Regex("""^(vercel\.json|netlify\.toml|now\.json|firebase\.json|angular\.json)$"""),
      Regex(""".*\.git(?:/.*)?$"""),
    )
  }
}

/**
 * Resolves a relative path strictly inside [root]. Returns null when unsafe. Shared by the
 * gate and [ProjectFileOps] so path safety has exactly one implementation.
 */
internal fun resolveInProject(root: File, rel: String): File? {
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
