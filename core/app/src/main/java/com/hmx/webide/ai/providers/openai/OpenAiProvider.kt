package com.hmx.webide.ai.providers.openai

import com.hmx.webide.ai.AiProvider
import com.hmx.webide.ai.errors.AiException
import com.hmx.webide.ai.errors.AuthenticationException
import com.hmx.webide.ai.errors.ModelNotFoundException
import com.hmx.webide.ai.errors.NetworkException
import com.hmx.webide.ai.errors.RateLimitException
import com.hmx.webide.ai.models.AiModel
import com.hmx.webide.ai.models.Capability
import com.hmx.webide.ai.models.ChatMessage
import com.hmx.webide.ai.models.ChatRequest
import com.hmx.webide.ai.models.ChatResponse
import com.hmx.webide.ai.models.Chunk
import com.hmx.webide.ai.models.Role
import com.hmx.webide.ai.models.Tool
import com.hmx.webide.ai.models.ToolCall
import com.hmx.webide.ai.models.ToolCallDelta
import com.hmx.webide.ai.models.Usage
import com.hmx.webide.ai.network.AiHttpClient
import com.hmx.webide.ai.network.HttpResponse
import com.hmx.webide.ai.storage.ProviderStorage
import com.hmx.webide.activities.HttpConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

open class OpenAiProvider(
  override val providerId: String,
  override val displayName: String,
  private val defaultBaseUrl: String,
  private val storage: ProviderStorage? = null,
  private val client: AiHttpClient = AiHttpClient(),
  private val chatEndpointPath: String = "v1/chat/completions",
  private val modelsEndpointPath: String = "v1/models",
) : AiProvider {

  override val capabilities: Set<Capability> = setOf(Capability.streaming, Capability.tools, Capability.functionCalling)

  private fun resolveBaseUrl(): String {
    val userBaseUrl = storage?.getBaseUrl(providerId) ?: ""
    return userBaseUrl.ifBlank { defaultBaseUrl }.trimEnd('/')
  }

  private fun buildUrl(baseUrl: String, path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

  override suspend fun chat(request: ChatRequest): ChatResponse {
    val base = resolveBaseUrl()
    val url = buildUrl(base, chatEndpointPath)
    val headers = authHeaders()
    val body = buildRequestBody(request)
    val config = HttpConfig(url = url, method = "POST", headers = headers, readTimeout = 600_000)
    val response = client.execute(config, body = body)
    if (response.code !in 200..299) throw mapError(response, request.model)
    return parseChatResponse(response.body)
  }

  override fun stream(request: ChatRequest): Flow<Chunk> {
    val base = resolveBaseUrl()
    val url = buildUrl(base, chatEndpointPath)
    val headers = authHeaders()
    val streamRequest = request.copy(stream = true)
    val body = buildRequestBody(streamRequest)
    val config = HttpConfig(url = url, method = "POST", headers = headers, readTimeout = 600_000)
      return client.stream(config, body = body).map { line ->
        val json = JSONObject(line)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        val content = delta?.optString("content", "") ?: ""
        val finish = choice?.optString("finish_reason")
        val toolCalls = delta?.optJSONArray("tool_calls")?.let { parseToolCallDeltas(it) }
        Chunk(content = content, finishReason = finish, toolCalls = toolCalls)
      }
  }

  override suspend fun listModels(): List<AiModel> {
    val base = resolveBaseUrl()
    val url = buildUrl(base, modelsEndpointPath)
    val headers = authHeaders()
    val config = HttpConfig(url = url, headers = headers)
    val response = client.execute(config)
    if (response.code !in 200..299) return emptyList()
    return parseModels(response.body)
  }

  override suspend fun testConnection(): Boolean {
    val base = resolveBaseUrl()
    val url = buildUrl(base, modelsEndpointPath)
    val headers = authHeaders()
    val config = HttpConfig(url = url, headers = headers)
    val response = client.execute(config)
    return response.code in 200..299
  }

  protected open fun authHeaders(): Map<String, String> {
    val key = storage?.getApiKey(providerId) ?: ""
    return if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap()
  }

  protected open fun buildRequestBody(request: ChatRequest): String {
    val arr = JSONArray()
    if (request.systemPrompt != null) {
      arr.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
    }
    request.messages.forEach { m ->
      val obj = JSONObject().put("role", m.role.name.lowercase()).put("content", m.content)
      m.toolCallId?.let { obj.put("tool_call_id", it) }
      m.toolCalls?.let { calls ->
        val tcArr = JSONArray()
        calls.forEach { tc ->
          tcArr.put(
            JSONObject().put("id", tc.id).put("type", "function").put(
              "function",
              JSONObject().put("name", tc.name).put("arguments", tc.arguments)
            )
          )
        }
        obj.put("tool_calls", tcArr)
      }
      arr.put(obj)
    }
    val json = JSONObject().apply {
      put("model", request.model)
      put("messages", arr)
      put("stream", request.stream)
      if (request.tools.isNotEmpty()) {
        put("tools", buildToolsJson(request.tools))
        put("tool_choice", "auto")
      }
      request.maxTokens?.let { put("max_tokens", it) }
      request.temperature?.let { put("temperature", it) }
    }
    return json.toString()
  }

  protected open fun parseChatResponse(body: String): ChatResponse {
    val json = JSONObject(body)
    val choice = json.getJSONArray("choices").getJSONObject(0)
    val msg = choice.getJSONObject("message")
    val role = Role.valueOf(msg.optString("role", "assistant"))
    val content = msg.optString("content", "")
    val toolCalls = if (msg.has("tool_calls")) parseToolCalls(msg.getJSONArray("tool_calls")) else null
    val usageJson = json.optJSONObject("usage")
    val usage = usageJson?.let {
      Usage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"), it.optInt("total_tokens"))
    }
    return ChatResponse(
      message = ChatMessage(role = role, content = content, toolCalls = toolCalls),
      model = json.optString("model"),
      usage = usage,
    )
  }

  private fun buildToolsJson(tools: List<Tool>): JSONArray {
    val arr = JSONArray()
    tools.forEach { tool ->
      val props = JSONObject()
      val required = JSONArray()
      tool.parameters.forEach { p ->
        props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
        if (p.required) required.put(p.name)
      }
      val func = JSONObject()
        .put("name", tool.name)
        .put("description", tool.description)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))
      arr.put(JSONObject().put("type", "function").put("function", func))
    }
    return arr
  }

  private fun parseToolCalls(arr: JSONArray): List<ToolCall> {
    val out = mutableListOf<ToolCall>()
    for (i in 0 until arr.length()) {
      val tc = arr.getJSONObject(i)
      val fn = tc.optJSONObject("function") ?: continue
      out.add(
        ToolCall(
          id = tc.optString("id", "call_$i"),
          name = fn.optString("name", ""),
          arguments = fn.optString("arguments", "{}"),
        )
      )
    }
    return out
  }

  private fun parseToolCallDeltas(arr: JSONArray): List<ToolCallDelta> {
    val out = mutableListOf<ToolCallDelta>()
    for (i in 0 until arr.length()) {
      val tc = arr.getJSONObject(i)
      val fn = tc.optJSONObject("function")
      out.add(
        ToolCallDelta(
          index = tc.optInt("index", i),
          id = tc.optString("id", null),
          name = fn?.optString("name", null),
          arguments = fn?.optString("arguments", null),
        )
      )
    }
    return out
  }

  protected open fun parseModels(body: String): List<AiModel> {
    val list = mutableListOf<AiModel>()
    val json = JSONObject(body)
    val data = json.optJSONArray("data") ?: return emptyList()
    for (i in 0 until data.length()) {
      val model = data.getJSONObject(i)
      list.add(AiModel(id = model.getString("id")))
    }
    return list
  }

  protected open fun mapError(response: HttpResponse, model: String?): AiException =
    com.hmx.webide.ai.network.ProviderErrorMapper.map(displayName, model, response)
}
