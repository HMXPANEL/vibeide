package com.hmx.webide.ai.terminal

import com.hmx.webide.ai.tools.ToolResult
import java.io.File

/**
 * Best-effort command policy layer for terminal sessions.
 *
 * Reuses the Phase 2 safety philosophy (central policy, no separate permission
 * system): the terminal tools already go through [ToolRegistry], and this gate
 * adds a thin guard against clearly dangerous system-level commands. True
 * sandboxing is provided by the Android app UID — a VibeIDE shell can only reach
 * files the app is allowed to touch — so this is a second, cheap line of defense.
 */
object TerminalSafety {

  // ponytail: minimal deny-list; expand when a real escape vector shows up.
  private val DENY = listOf(
    Regex("""\brm\s+(-rf|--recursive)\s+/(\s|$)"""),
    Regex("""\bmkfs"""),
    Regex("""\bdd\s+if="""),
    Regex("""(^|\s)/(system|proc|sys|dev)(\s|/|$)"""),
    Regex("""\bchmod\s+-R\s+777\s+/"""),
    Regex("""\bmount\s+/"""),
  )

  /** Returns an error [ToolResult] if [command] is blocked, otherwise null. */
  fun check(command: String, projectDir: File): ToolResult? {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return ToolResult.error("INVALID_ARGUMENT", "empty terminal command")
    if (DENY.any { it.containsMatchIn(trimmed) }) {
      return ToolResult.error("DENIED", "command blocked by terminal policy: '$trimmed'")
    }
    return null
  }
}
