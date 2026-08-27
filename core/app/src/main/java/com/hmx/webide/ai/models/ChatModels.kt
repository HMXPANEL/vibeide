package com.hmx.webide.ai.models

enum class Role { system, user, assistant, tool }

data class ChatMessage(
  val role: Role,
  val content: String,
  /** Present on assistant turns that requested tool execution. */
  val toolCalls: List<ToolCall>? = null,
  /** Present on tool-result turns, links the result to its call. */
  val toolCallId: String? = null,
)

/** A file-operation tool the AI can invoke. Serialized to OpenAI `tools` format. */
data class Tool(
  val name: String,
  val description: String,
  val parameters: List<ToolParameter>,
)

data class ToolParameter(
  val name: String,
  val type: String,
  val description: String,
  val required: Boolean = true,
)

/** A concrete tool invocation requested by the model. */
data class ToolCall(
  val id: String,
  val name: String,
  val arguments: String,
)

/** A partial tool call emitted inside a streaming chunk (OpenAI indexes them). */
data class ToolCallDelta(
  val index: Int,
  val id: String? = null,
  val name: String? = null,
  val arguments: String? = null,
)

data class ChatRequest(
  val model: String,
  val messages: List<ChatMessage>,
  val systemPrompt: String? = null,
  val stream: Boolean = false,
  val maxTokens: Int? = null,
  val temperature: Float? = null,
  val tools: List<Tool> = emptyList(),
)

data class ChatResponse(
  val message: ChatMessage,
  val model: String? = null,
  val usage: Usage? = null,
)

data class Chunk(
  val content: String,
  val finishReason: String? = null,
  val toolCalls: List<ToolCallDelta>? = null,
)

data class Usage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  val totalTokens: Int = 0,
)

data class AiModel(
  val id: String,
  val name: String? = null,
  val providerId: String? = null,
)

enum class Capability {
  streaming, vision, tools, functionCalling
}
