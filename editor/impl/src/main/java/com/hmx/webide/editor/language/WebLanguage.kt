package com.hmx.webide.editor.language

/**
 * A lightweight [IDELanguage] for web source files (HTML, CSS, JavaScript, Markdown).
 *
 * There are no tree-sitter grammars for these languages in VibeIDE yet, so this language
 * provides the base editor behaviour (tab size, formatting via an optional LSP server,
 * completion hooks) without any Android-specific infrastructure or native dependencies.
 */
class WebLanguage(val type: String) : IDELanguage() {

  override fun getTabSize(): Int = 2
}