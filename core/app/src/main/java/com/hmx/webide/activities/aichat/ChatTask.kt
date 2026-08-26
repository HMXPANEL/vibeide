package com.hmx.webide.activities.aichat

/**
 * Lifecycle of a single AI task triggered from the chat screen.
 *
 * QUEUED -> RUNNING -> WORKING -> COMPLETED
 *                       |
 *                       -> FAILED / INTERRUPTED
 *
 * A task is persisted per project so it survives the Chat screen being recreated or the app
 * being backgrounded; an in-flight task that is destroyed by a lifecycle change becomes
 * INTERRUPTED and can be resumed with Continue.
 */
data class ChatTask(
  val id: String,
  val prompt: String,
  val status: Status,
  val partial: String = "",
  val error: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  enum class Status { QUEUED, RUNNING, WORKING, COMPLETED, FAILED, INTERRUPTED }
}
