package com.hmx.webide.ai.tools

/**
 * Structured result of a tool executed by the app on behalf of the AI.
 *
 * The AI provider protocol still receives a plain tool message; [toContent] renders the same
 * information as text, so no provider/serialization change is needed (backward compatible).
 */
data class ToolResult(
  val isError: Boolean,
  val code: String? = null,
  val message: String,
) {
  /** Text the model receives as the tool message content. */
  fun toContent(): String = if (isError && code != null) "[$code] $message" else message

  companion object {
    fun ok(message: String) = ToolResult(isError = false, code = null, message = message)
    fun error(code: String, message: String) = ToolResult(isError = true, code = code, message = message)
    fun timeout(toolName: String, seconds: Long) =
      error("TIMEOUT", "Tool '$toolName' timed out after ${seconds}s")
  }
}
