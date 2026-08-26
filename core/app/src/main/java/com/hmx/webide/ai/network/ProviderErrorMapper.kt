package com.hmx.webide.ai.network

import com.hmx.webide.ai.errors.AiException
import com.hmx.webide.ai.errors.AuthenticationException
import com.hmx.webide.ai.errors.ModelNotFoundException
import com.hmx.webide.ai.errors.NetworkException
import com.hmx.webide.ai.errors.ProviderException
import com.hmx.webide.ai.errors.QuotaException
import com.hmx.webide.ai.errors.RateLimitException
import org.json.JSONObject
import org.slf4j.LoggerFactory

/**
 * Maps a provider HTTP failure to the most accurate [AiException] by actually reading the
 * provider's response body — token balance/quota problems are separated from temporary
 * rate limits, auth errors, bad requests and server errors.
 *
 * Logged details never include API keys (keys travel only in request headers).
 */
object ProviderErrorMapper {

  private val log = LoggerFactory.getLogger(ProviderErrorMapper::class.java)

  private val QUOTA_HINTS = listOf(
    "insufficient_quota", "quota", "balance", "billing", "credit",
    "exceeded your current quota", "payment", "arrear", "欠费"
  )

  fun map(provider: String, model: String?, response: HttpResponse): AiException {
    val code = response.code
    val err = parseErrorBody(response.body)
    val type = err.first
    val message = err.second?.take(300)

    // Development-grade logging of the real cause (safe fields only).
    log.warn(
      "AI provider error: provider={} model={} http={} type={} retryAfter={} body={}",
      provider, model ?: "?", code, type ?: "?",
      response.header("retry-after") ?: "-",
      response.body.take(500)
    )

    return when {
      code == 402 ->
        QuotaException(uiMessage("Insufficient balance or quota at the provider", message))
      code == 429 && isQuota(type, response.body) ->
        QuotaException(uiMessage("Provider quota/balance exhausted", message))
      code == 429 -> {
        val retryAfter = response.header("retry-after")?.trim()?.toLongOrNull()
        RateLimitException(
          uiMessage("Rate limited (requests-per-minute/token-per-minute limit)", message),
          retryAfterSeconds = retryAfter)
      }
      code == 401 ->
        AuthenticationException(uiMessage("Invalid API key", message))
      code == 403 && isQuota(type, response.body) ->
        QuotaException(uiMessage("Provider quota/balance exhausted", message))
      code == 403 ->
        AuthenticationException(uiMessage("Forbidden — check API key permissions/region", message))
      code == 404 ->
        ModelNotFoundException(uiMessage("Model not found on this provider", message))
      code == 400 || code == 422 ->
        ProviderException(uiMessage("Request rejected as malformed/too large", message))
      code in 500..599 ->
        NetworkException(uiMessage("Provider server error (${code})", message))
      else ->
        NetworkException(uiMessage("HTTP ${code}", message))
    }
  }

  /** True when a 429 actually means exhausted quota/balance instead of a temporary limit. */
  private fun isQuota(type: String?, body: String): Boolean {
    val haystack = ((type ?: "") + " " + body.take(1000)).lowercase()
    return QUOTA_HINTS.any { haystack.contains(it) }
  }

  /** Pulls `error.message` / `error.type` (OpenAI-style) with tolerant fallbacks. */
  private fun parseErrorBody(body: String): Pair<String?, String?> {
    if (body.isBlank()) return null to null
    return runCatching {
      val json = JSONObject(body)
      val errObj = json.optJSONObject("error")
      val type = errObj?.optString("type", null)?.takeIf { it.isNotBlank() }
        ?: errObj?.optString("code", null)?.takeIf { it.isNotBlank() }
        ?: json.optString("code", null)?.takeIf { it.isNotBlank() }
      val message = errObj?.optString("message", null)?.takeIf { it.isNotBlank() }
        ?: json.optString("message", null)?.takeIf { it.isNotBlank() }
      type to message
    }.getOrDefault(null to null)
  }

  private fun uiMessage(default: String, detail: String?): String =
    if (detail.isNullOrBlank()) default else "$default — $detail"
}
