package com.hmx.webide.ai

import com.hmx.webide.ai.errors.AiException
import com.hmx.webide.ai.errors.NetworkException
import com.hmx.webide.ai.models.AiModel
import com.hmx.webide.ai.models.Capability
import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.ChatRequest
import com.hmx.webide.ai.models.ChatResponse
import com.hmx.webide.ai.models.Chunk
import kotlinx.coroutines.flow.Flow

interface AiProvider {

  val providerId: String
  val displayName: String
  val capabilities: Set<Capability>

  suspend fun chat(request: ChatRequest): ChatResponse

  fun stream(request: ChatRequest): Flow<Chunk>

  suspend fun listModels(): List<AiModel>

  suspend fun testConnection(): Boolean

  fun buildChatRequest(
    model: String,
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    stream: Boolean = false,
  ): ChatRequest = ChatRequest(
    model = model,
    messages = messages,
    systemPrompt = systemPrompt,
    stream = stream,
  )

  fun handleError(throwable: Throwable): AiException =
    when (throwable) {
      is AiException -> throwable
      else -> NetworkException(throwable.message ?: "Unknown error", throwable)
    }
}
