package com.hmx.webide.ai.engine

import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.ChatResponse
import com.hmx.webide.ai.models.Chunk
import com.hmx.webide.ai.models.Role
import com.hmx.webide.ai.pipeline.ContextPipeline
import kotlinx.coroutines.flow.collect

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
    execute: suspend (com.hmx.webide.ai.models.ToolCall) -> String,
  ): ChatResponse {
    val userMsg = ChatMessage(Role.user, content)
    messages.add(userMsg)
    trimHistory()
    try {
      while (true) {
        val request = engine.buildChatRequest(model, messages, systemPrompt, stream = true, tools = tools)
        val accumulated = StringBuilder()
        val calls = mutableListOf<com.hmx.webide.ai.models.ToolCallDelta>()
        engine.stream(request).collect { chunk ->
          if (chunk.content.isNotEmpty()) {
            accumulated.append(chunk.content)
            onChunk(chunk.content)
          }
          chunk.toolCalls?.let { deltas ->
            for (d in deltas) {
              while (calls.size <= d.index) calls.add(com.hmx.webide.ai.models.ToolCallDelta(calls.size))
              val slot = calls[d.index]
              d.id?.let { calls[d.index] = slot.copy(id = it) }
              d.name?.let { calls[d.index] = calls[d.index].copy(name = it) }
              d.arguments?.let { calls[d.index] = calls[d.index].copy(arguments = (calls[d.index].arguments ?: "") + it) }
            }
          }
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
          return ChatResponse(message = assistantMsg)
        }
        finalCalls.forEach { call ->
          val result = execute(call)
          messages.add(
            ChatMessage(role = Role.tool, content = result, toolCallId = call.id)
          )
          trimHistory()
        }
      }
    } catch (t: Throwable) {
      messages.remove(userMsg)
      throw t
    }
  }

  private fun trimHistory() {
    while (messages.size > maxHistory) {
      messages.removeAt(0)
    }
  }
}
