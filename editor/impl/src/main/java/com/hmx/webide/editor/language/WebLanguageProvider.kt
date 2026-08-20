package com.hmx.webide.editor.language

import java.io.File

/**
 * Maps web file extensions to a [WebLanguage]. Registered as the fallback language for
 * web source files that have no tree-sitter grammar.
 */
object WebLanguageProvider {

  private val WEB_TYPES = mapOf(
    "html" to "html",
    "htm" to "html",
    "css" to "css",
    "js" to "javascript",
    "mjs" to "javascript",
    "cjs" to "javascript",
    "ts" to "typescript",
    "tsx" to "typescript",
    "jsx" to "javascript",
    "vue" to "html",
    "md" to "markdown",
    "markdown" to "markdown",
  )

  private val instances = mutableMapOf<String, WebLanguage>()

  fun hasLanguage(file: File): Boolean = file.extension.lowercase() in WEB_TYPES

  fun forFile(file: File): WebLanguage {
    val type = WEB_TYPES[file.extension.lowercase()] ?: "plain"
    return instances.getOrPut(type) { WebLanguage(type) }
  }
}