package com.hmx.webide.ai

import com.google.common.truth.Truth.assertThat
import com.hmx.webide.ai.engine.AiEngine
import com.hmx.webide.ai.engine.ChatEngine
import com.hmx.webide.ai.engine.retryOnceOnRateLimit
import com.hmx.webide.ai.errors.AuthenticationException
import com.hmx.webide.ai.errors.QuotaException
import com.hmx.webide.ai.errors.RateLimitException
import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.ChatRequest
import com.hmx.webide.ai.models.Chunk
import com.hmx.webide.ai.models.Role
import com.hmx.webide.ai.models.Tool
import com.hmx.webide.ai.models.ToolCall
import com.hmx.webide.ai.models.ToolCallDelta
import com.hmx.webide.ai.models.ToolParameter
import com.hmx.webide.ai.tools.ProjectFileOps
import com.hmx.webide.ai.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File

class Phase1ToolSafetyTest {

  private val tmpDirs = mutableListOf<File>()

  @After
  fun tearDown() {
    tmpDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(): File {
    val d = File(System.getProperty("java.io.tmpdir"), "vibeide_pf_${System.nanoTime()}")
    d.mkdirs()
    tmpDirs.add(d)
    return d
  }

  // ---------------------------------------------------------------------------
  // ToolResult structure + ProjectFileOps typed errors
  // ---------------------------------------------------------------------------

  @Test
  fun `ToolResult toContent formats error with code`() {
    assertThat(ToolResult.error("X", "boom").toContent()).isEqualTo("[X] boom")
    assertThat(ToolResult.ok("fine").toContent()).isEqualTo("fine")
  }

  @Test
  fun `write_file success increments changedFiles`() = runBlocking {
    val dir = tempDir()
    val ops = ProjectFileOps(dir)
    val r = ops.dispatch(ToolCall("c1", "write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}"))
    assertThat(r.isError).isFalse()
    assertThat(ops.changedFiles).isEqualTo(1)
    assertThat(File(dir, "a.txt").readText()).isEqualTo("hi")
  }

  @Test
  fun `read_file missing returns FILE_NOT_FOUND`() = runBlocking {
    val ops = ProjectFileOps(tempDir())
    val r = ops.dispatch(ToolCall("c", "read_file", "{\"path\":\"nope.txt\"}"))
    assertThat(r.isError).isTrue()
    assertThat(r.code).isEqualTo("FILE_NOT_FOUND")
  }

  @Test
  fun `dotdot path returns INVALID_PATH`() = runBlocking {
    val ops = ProjectFileOps(tempDir())
    val r = ops.dispatch(ToolCall("c", "read_file", "{\"path\":\"../secret\"}"))
    assertThat(r.code).isEqualTo("INVALID_PATH")
  }

  @Test
  fun `absolute path returns INVALID_PATH`() = runBlocking {
    val ops = ProjectFileOps(tempDir())
    val r = ops.dispatch(ToolCall("c", "read_file", "{\"path\":\"/etc/passwd\"}"))
    assertThat(r.code).isEqualTo("INVALID_PATH")
  }

  @Test
  fun `unknown tool returns UNKNOWN_TOOL`() = runBlocking {
    val ops = ProjectFileOps(tempDir())
    val r = ops.dispatch(ToolCall("c", "hack", "{}"))
    assertThat(r.code).isEqualTo("UNKNOWN_TOOL")
  }

  // ---------------------------------------------------------------------------
  // Shared provider retry (used by Chat + Build/Plan)
  // ---------------------------------------------------------------------------

  @Test
  fun `retryOnceOnRateLimit retries 429 exactly once`() = runBlocking {
    var n = 0
    val r = retryOnceOnRateLimit {
      n++
      if (n == 1) throw RateLimitException("rate", retryAfterSeconds = 1L) else "ok"
    }
    assertThat(r).isEqualTo("ok")
    assertThat(n).isEqualTo(2)
  }

  @Test
  fun `retryOnceOnRateLimit does not loop on repeated 429`() = runBlocking {
    var n = 0
    try {
      retryOnceOnRateLimit { n++; throw RateLimitException("rate", retryAfterSeconds = 1L) }
      assertThat(false).isTrue() // should not reach
    } catch (e: RateLimitException) {
      // expected
    }
    assertThat(n).isEqualTo(2) // initial + one retry
  }

  @Test
  fun `retryOnceOnRateLimit does not retry QuotaException`() = runBlocking {
    var n = 0
    try { retryOnceOnRateLimit { n++; throw QuotaException("q") } } catch (e: QuotaException) {}
    assertThat(n).isEqualTo(1)
  }

  @Test
  fun `retryOnceOnRateLimit does not retry AuthenticationException`() = runBlocking {
    var n = 0
    try { retryOnceOnRateLimit { n++; throw AuthenticationException("a") } } catch (e: AuthenticationException) {}
    assertThat(n).isEqualTo(1)
  }

  @Test
  fun `retryOnceOnRateLimit does not retry tool errors`() = runBlocking {
    var n = 0
    try { retryOnceOnRateLimit { n++; throw RuntimeException("boom") } } catch (e: RuntimeException) {}
    assertThat(n).isEqualTo(1)
  }

  // ---------------------------------------------------------------------------
  // runWithTools: per-tool timeout + loop cap + build 429 retry (no duplicate ops)
  // ---------------------------------------------------------------------------

  private val tools = listOf(
    Tool("write_file", "write", listOf(ToolParameter("path", "string", "p", true))),
  )

  private class ScriptedEngine(private val onStream: (ChatRequest) -> Flow<Chunk>) : AiEngine() {
    override fun buildChatRequest(
      model: String,
      messages: List<ChatMessage>,
      systemPrompt: String?,
      stream: Boolean,
      tools: List<Tool>,
    ): ChatRequest = ChatRequest(model, messages, systemPrompt, stream, tools)

    override fun stream(request: ChatRequest): Flow<Chunk> = onStream(request)
  }

  @Test
  fun `per-tool timeout does not crash the task and feeds TIMEOUT to AI`() = runBlocking {
    val calls = mutableListOf<ToolCall>()
    val engine = ScriptedEngine { _ ->
      flowOf(Chunk(content = "", toolCalls = listOf(ToolCallDelta(index = 0, id = "c1", name = "hang", arguments = "{}"))))
    }
    val chat = ChatEngine(engine)
    val resp = chat.runWithTools("m1", "go", null, tools, {}, toolTimeoutMs = 200) { call ->
      calls.add(call)
      delay(500) // longer than the 200ms timeout -> cancelled
      ToolResult.ok("done")
    }
    // Task keeps going until the round cap and returns a normal message (no exception).
    assertThat(resp.message.content).contains("Reached maximum tool rounds")
    assertThat(calls).hasSize(20)
    // The AI was told about the timeout via a typed tool result.
    assertThat(chat.history().any { it.role == Role.tool && it.content.startsWith("[TIMEOUT]") }).isTrue()
  }

  @Test
  fun `tool loop is capped at MAX_TOOL_ROUNDS`() = runBlocking {
    val calls = mutableListOf<ToolCall>()
    val engine = ScriptedEngine { request ->
      if (request.tools.isEmpty()) flowOf(Chunk(content = "final", finishReason = "stop"))
      else flowOf(Chunk(content = "", toolCalls = listOf(ToolCallDelta(index = 0, id = "c1", name = "write_file", arguments = "{\"path\":\"a\",\"content\":\"x\"}"))))
    }
    val chat = ChatEngine(engine)
    val resp = chat.runWithTools("m1", "go", null, tools, {}, toolTimeoutMs = 5000) { call ->
      calls.add(call); ToolResult.ok("ok")
    }
    assertThat(calls).hasSize(20) // exactly 20 tool rounds, never a 21st
    assertThat(resp.message.content).contains("Reached maximum tool rounds")
  }

  @Test
  fun `build 429 retry does not re-execute an already-completed file operation`() = runBlocking {
    val calls = mutableListOf<ToolCall>()
    var streamCount = 0
    val engine = ScriptedEngine { request ->
      streamCount++
      when {
        // Round 1: model asks to write a file -> tool executes successfully.
        streamCount == 1 ->
          flowOf(Chunk(content = "", toolCalls = listOf(ToolCallDelta(index = 0, id = "c1", name = "write_file", arguments = "{\"path\":\"a\",\"content\":\"x\"}"))))
        // Round 2: provider hits a 429 while producing the final answer.
        streamCount == 2 -> throw RateLimitException("rate", retryAfterSeconds = 1L)
        // Retry (and any later round): returns the final answer, no tool calls.
        else -> flowOf(Chunk(content = "done", finishReason = "stop"))
      }
    }
    val chat = ChatEngine(engine)
    val resp = chat.runWithTools("m1", "go", null, tools, {}, toolTimeoutMs = 5000) { call ->
      calls.add(call); ToolResult.ok("written")
    }
    // The write ran exactly once; the 429 retry only re-issued the provider call, not the tool.
    assertThat(calls).hasSize(1)
    assertThat(resp.message.content).isEqualTo("done")
  }

  @Test
  fun `tool result is fed back to the AI as message content`() = runBlocking {
    val engine = ScriptedEngine { request ->
      if (request.tools.isEmpty()) flowOf(Chunk(content = "final", finishReason = "stop"))
      else flowOf(Chunk(content = "", toolCalls = listOf(ToolCallDelta(index = 0, id = "c1", name = "write_file", arguments = "{\"path\":\"a\",\"content\":\"x\"}"))))
    }
    val chat = ChatEngine(engine)
    chat.runWithTools("m1", "go", null, tools, {}, toolTimeoutMs = 5000) { ToolResult.ok("written") }
    val toolMsg = chat.history().first { it.role == Role.tool }
    assertThat(toolMsg.content).isEqualTo("written")
    assertThat(toolMsg.toolCallId).isEqualTo("c1")
  }
}
