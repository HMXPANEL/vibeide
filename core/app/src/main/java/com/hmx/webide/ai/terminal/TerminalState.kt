package com.hmx.webide.ai.terminal

/** Lifecycle/run state of a command or session, reported back to the AI. */
enum class TerminalState {
  RUNNING,
  COMPLETED,
  FAILED,
  STOPPED,
}

/** Marker printed by the launcher wrapper after a command finishes: `__VIBE_DONE_<code>__`. */
internal const val DONE_MARKER = "__VIBE_DONE_"
internal const val DONE_MARKER_END = "__"
