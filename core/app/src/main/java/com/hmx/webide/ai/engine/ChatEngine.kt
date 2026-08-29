package com.hmx.webide.ai.engine

import com.hmx.webide.ai.errors.RateLimitException
import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.ChatResponse
import com.hmx.webide.ai.models.Chunk
import com.hmx.webide.ai.models.Role
import com.hmx.webide.ai.pipeline.ContextPipeline
import com.hmx.webide.ai.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class ChatEngine(
  private val engine: AiEngine,
  private val maxHistory: Int = 50,
) {

  private val messages = mutableListOf<ChatMessage>()

  fun history(): List<ChatMessage> = messages.toList()

  /** Replaces in-memory history (used to restore a persisted project conversation). */
  fun restoreHistory(history: List<ChatMessage>) {
    messages.clear()
    messages.addAll(history)
    trimHistory()
  }

  suspend fun send(
    model: String,
    content: String,
    systemPrompt: String? = null,
  ): ChatResponse {
    val userMsg = ChatMessage(Role.user, content)
    messages.add(userMsg)
    trimHistory()
    try {
      val request = engine.buildChatRequest(model, messages, systemPrompt)
      val response = engine.chat(request)
      messages.add(response.message)
      trimHistory()
      return response
    } catch (t: Throwable) {
      // Keep history consistent so a caller-side retry doesn't duplicate the user turn.
      messages.remove(userMsg)
      throw t
    }
  }

  suspend fun stream(
    model: String,
    content: String,
    systemPrompt: String? = null,
    onChunk: (Chunk) -> Unit = {},
  ): ChatResponse {
    val userMsg = ChatMessage(Role.user, content)
    messages.add(userMsg)
    trimHistory()
    try {
      val request = engine.buildChatRequest(model, messages, systemPrompt, stream = true)
      val fullContent = StringBuilder()
      engine.stream(request).collect { chunk ->
        fullContent.append(chunk.content)
        onChunk(chunk)
      }
      val assistantMsg = ChatMessage(Role.assistant, fullContent.toString())
      messages.add(assistantMsg)
      trimHistory()
      return ChatResponse(message = assistantMsg)
    } catch (t: Throwable) {
      // Keep history consistent so a caller-side retry doesn't duplicate the user turn.
      messages.remove(userMsg)
      throw t
    }
  }

  suspend fun sendQuery(
    model: String,
    query: String,
    pipeline: ContextPipeline,
  ): ChatResponse {
    val pipelineMessages = pipeline.processQuery(query)
    val systemPrompt = pipelineMessages.firstOrNull { it.role == Role.system }?.content
    val userContent = pipelineMessages.firstOrNull { it.role == Role.user }?.content ?: query
    return send(model, userContent, systemPrompt)
  }

  suspend fun streamQuery(
    model: String,
    query: String,
    pipeline: ContextPipeline,
    onChunk: (Chunk) -> Unit = {},
  ): ChatResponse {
    val pipelineMessages = pipeline.processQuery(query)
    val systemPrompt = pipelineMessages.firstOrNull { it.role == Role.system }?.content
    val userContent = pipelineMessages.firstOrNull { it.role == Role.user }?.content ?: query
    return stream(model, userContent, systemPrompt, onChunk)
  }

  fun clear() { messages.clear() }

  /**
   * Runs a request with file-operation tools. Loops: send messages (+tools), let the model
   * call tools, execute each call through [execute] (project-scoped, app-controlled), feed the
   * results back, and repeat until the model returns a final answer with no tool calls.
   * Streams the assistant text to [onChunk] so long tasks stay responsive.
   */
  suspend fun runWithTools(
    model: String,
    content: String,
    systemPrompt: String? = null,
    tools: List<com.hmx.webide.ai.models.Tool>,
    onChunk: (String) -> Unit = {},
    toolTimeoutMs: Long = 30_000,
    execute: suspend (com.hmx.webide.ai.models.ToolCall) -> ToolResult,
  ): ChatResponse {
    val userMsg = ChatMessage(Role.user, content)
    messages.add(userMsg)
    trimHistory()
    try {
      var rounds = 0
      while (true) {
        rounds++
        // Past the cap, ask the model for a final answer WITHOUT tools so the loop can't restart.
        val requestTools = if (rounds <= MAX_TOOL_ROUNDS) tools else emptyList()
        val request = engine.buildChatRequest(model, messages, systemPrompt, stream = true, tools = requestTools)
        // Retry only a genuine 429 (with Retry-After) on the provider call itself. Tool results
        // already executed in earlier rounds stay in history, so a retry never re-runs a tool
        // (no duplicate file operations). Genuine cancellation propagates untouched.
        val (accumulated, calls) = retryOnceOnRateLimit {
          val acc = StringBuilder()
          val collected = mutableListOf<com.hmx.webide.ai.models.ToolCallDelta>()
          engine.stream(request).collect { chunk ->
            if (chunk.content.isNotEmpty()) {
              acc.append(chunk.content)
              onChunk(chunk.content)
            }
            chunk.toolCalls?.let { deltas ->
              for (d in deltas) {
                while (collected.size <= d.index) collected.add(com.hmx.webide.ai.models.ToolCallDelta(collected.size))
                val slot = collected[d.index]
                d.id?.let { collected[d.index] = slot.copy(id = it) }
                d.name?.let { collected[d.index] = collected[d.index].copy(name = it) }
                d.arguments?.let { collected[d.index] = collected[d.index].copy(arguments = (collected[d.index].arguments ?: "") + it) }
              }
            }
          }
          acc to collected
        }
        val finalCalls = calls.filter { it.name != null }.map {
          com.hmx.webide.ai.models.ToolCall(
            id = it.id ?: "call_${it.index}",
            name = it.name ?: "",
            arguments = it.arguments ?: "{}",
          )
        }
        val assistantMsg = ChatMessage(
          role = Role.assistant,
          content = accumulated.toString(),
          toolCalls = finalCalls.ifEmpty { null },
        )
        messages.add(assistantMsg)
        trimHistory()
        if (finalCalls.isEmpty()) {
          val finalContent = if (rounds > MAX_TOOL_ROUNDS) {
            "${assistantMsg.content}\n\n⚠ Reached maximum tool rounds ($MAX_TOOL_ROUNDS); stopped to avoid a runaway task."
          } else assistantMsg.content
          return ChatResponse(message = ChatMessage(role = Role.assistant, content = finalContent))
        }
        finalCalls.forEach { call ->
          val result = try {
            withTimeout(toolTimeoutMs) { execute(call) }
          } catch (e: TimeoutCancellationException) {
            // A slow tool must not abort the whole task: report a typed TIMEOUT and let the
            // model decide what to do next. Genuine outer cancellation is NOT caught here.
            ToolResult.timeout(call.name, toolTimeoutMs / 1000)
          }
          messages.add(
            ChatMessage(role = Role.tool, content = result.toContent(), toolCallId = call.id)
          )
          trimHistory()
        }
      }
    } catch (t: Throwable) {
      messages.remove(userMsg)
      throw t
    }
  }

  private companion object {
    const val MAX_TOOL_ROUNDS = 20
  }

  private fun trimHistory() {
    while (messages.size > maxHistory) {
      messages.removeAt(0)
    }
  }
}

/**
 * Shared provider-retry used by both Chat and Build/Plan.
 *
 * On a genuine 429 carrying Retry-After, waits (capped at 60s) and retries at most once — the
 * exact behavior Chat already had. Other errors (QuotaException, AuthenticationException,
 * ModelNotFoundException, bad-request ProviderException, tool errors, real cancellation) are NOT
 * retried and propagate unchanged. This keeps a single retry policy instead of two divergent ones.
 */
internal suspend fun <T> retryOnceOnRateLimit(
  onRetry: suspend (Long) -> Unit = {},
  block: suspend () -> T,
): T {
  var attempt = 0
  while (true) {
    try {
      return block()
    } catch (e: RateLimitException) {
      if (attempt < 1 && (e.retryAfterSeconds ?: 0L) > 0L) {
        attempt++
        val wait = (e.retryAfterSeconds ?: 1L).coerceAtMost(60L)
        onRetry(wait)
        delay(wait * 1000L)
      } else throw e
    }
  }
}
