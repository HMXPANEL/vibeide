package com.hmx.webide.activities.aichat

data class ChatMessage(
  val role: String, // "user" | "assistant" | "system"
  val content: String
)
