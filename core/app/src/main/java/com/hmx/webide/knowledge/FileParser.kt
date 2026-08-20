package com.hmx.webide.knowledge

import com.hmx.webide.knowledge.model.DeclarationModel
import com.hmx.webide.knowledge.model.FileModel
import com.hmx.webide.knowledge.model.SymbolKind
import java.io.File

/**
 * Extracts lightweight symbol declarations from web source files for the symbol index.
 *
 * Recognition per file type:
 * - JS/TS: `function`, `class`, `const`/`let`/`var` declarations, and `export` targets.
 * - HTML: element `id` and `class` attributes.
 * - CSS: top-level selectors.
 * - JSON: top-level object keys.
 * - Markdown: headings.
 */
object FileParser {

  fun parse(file: File, root: File, content: String): FileModel {
    val relPath = file.relativeTo(root).path
    val ext = file.extension.lowercase()
    val declarations = when (ext) {
      "js", "mjs", "cjs", "ts", "tsx", "jsx" -> parseJavaScript(content, relPath)
      "html", "htm" -> parseHtml(content, relPath)
      "css" -> parseCss(content, relPath)
      "json" -> parseJson(content, relPath)
      "md", "markdown" -> parseMarkdown(content, relPath)
      else -> emptyList()
    }
    return FileModel(
      path = file.absolutePath,
      relativePath = relPath,
      declarations = declarations,
    )
  }

  private fun parseJavaScript(content: String, relPath: String): List<DeclarationModel> {
    val result = mutableListOf<DeclarationModel>()
    val pattern =
      Regex("""(?m)^\s*(?:export\s+)?(?:async\s+)?(?:function\s+([A-Za-z_$][\w$]*)|class\s+([A-Za-z_$][\w$]*)|const\s+([A-Za-z_$][\w$]*)\s*=|let\s+([A-Za-z_$][\w$]*)\s*=|var\s+([A-Za-z_$][\w$]*)\s*=|export\s+default\s+([A-Za-z_$][\w$]*))""")
    for (m in pattern.findAll(content)) {
      val (line, col) = lineColOf(content, m.range.first)
      val name = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
      val kind = when {
        m.groupValues[2].isNotEmpty() -> SymbolKind.CLASS
        m.groupValues[1].isNotEmpty() -> SymbolKind.FUNCTION
        else -> SymbolKind.VARIABLE
      }
      result.add(decl(kind, name, "$relPath#$kind:$name", line, col))
    }
    return result
  }

  private fun parseHtml(content: String, relPath: String): List<DeclarationModel> {
    val result = mutableListOf<DeclarationModel>()
    Regex("""\bid\s*=\s*["']([^"']+)["']""").findAll(content).forEach {
      val (line, col) = lineColOf(content, it.range.first)
      result.add(decl(SymbolKind.KEY, it.groupValues[1], "$relPath#id:${it.groupValues[1]}", line, col))
    }
    Regex("""\bclass\s*=\s*["']([^"']+)["']""").findAll(content).forEach {
      val classes = it.groupValues[1].split(Regex("\\s+")).filter { c -> c.isNotEmpty() }
      classes.forEach { c ->
        val (line, col) = lineColOf(content, it.range.first)
        result.add(decl(SymbolKind.KEY, c, "$relPath#class:$c", line, col))
      }
    }
    return result
  }

  private fun parseCss(content: String, relPath: String): List<DeclarationModel> {
    val result = mutableListOf<DeclarationModel>()
    Regex("""(?m)^\s*([.#]?[A-Za-z_][\w-]*)[^{]*\{""").findAll(content).forEach {
      val selector = it.groupValues[1].trim()
      if (selector.isEmpty()) return@forEach
      val (line, col) = lineColOf(content, it.range.first)
      result.add(decl(SymbolKind.SELECTOR, selector, "$relPath#sel:$selector", line, col))
    }
    return result
  }

  private fun parseJson(content: String, relPath: String): List<DeclarationModel> {
    val result = mutableListOf<DeclarationModel>()
    Regex("""(?m)^\s*"([^"]+)"\s*:""").findAll(content).forEach {
      val (line, col) = lineColOf(content, it.range.first)
      result.add(decl(SymbolKind.KEY, it.groupValues[1], "$relPath#key:${it.groupValues[1]}", line, col))
    }
    return result
  }

  private fun parseMarkdown(content: String, relPath: String): List<DeclarationModel> {
    val result = mutableListOf<DeclarationModel>()
    Regex("""(?m)^(#{1,6})\s+(.+)$""").findAll(content).forEach {
      val heading = it.groupValues[2].trim()
      val (line, col) = lineColOf(content, it.range.first)
      result.add(decl(SymbolKind.HEADING, heading, "$relPath#heading:$heading", line, col))
    }
    return result
  }

  private fun decl(kind: SymbolKind, name: String, fqn: String, line: Int, column: Int): DeclarationModel {
    return DeclarationModel(kind = kind, name = name, fqn = fqn, line = line, column = column)
  }

  private fun lineColOf(content: String, index: Int): Pair<Int, Int> {
    val upTo = content.substring(0, index)
    val line = upTo.count { it == '\n' } + 1
    val lastNl = upTo.lastIndexOf('\n')
    val col = index - lastNl
    return line to col
  }
}