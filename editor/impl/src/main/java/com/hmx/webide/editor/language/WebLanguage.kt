package com.hmx.webide.editor.language

import com.hmx.webide.editor.language.utils.CommonSymbolPairs
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_STRONG
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.widget.SymbolPairMatch

/**
 * A lightweight [IDELanguage] for web source files (HTML, CSS, JavaScript, Markdown).
 *
 * There are no tree-sitter grammars for these languages in VibeIDE yet, so this language
 * provides the base editor behaviour (tab size, formatting via an optional LSP server,
 * completion hooks) without any Android-specific infrastructure or native dependencies.
 */
class WebLanguage(val type: String) : IDELanguage() {

  override fun getTabSize(): Int = 2

  override fun getAnalyzeManager(): AnalyzeManager = EmptyLanguage.EmptyAnalyzeManager()

  override fun getInterruptionLevel(): Int = INTERRUPTION_LEVEL_STRONG

  override fun getNewlineHandlers(): Array<NewlineHandler>? = null

  override fun getSymbolPairs(): SymbolPairMatch = CommonSymbolPairs()

  override fun destroy() = Unit
}