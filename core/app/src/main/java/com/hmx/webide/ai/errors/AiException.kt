package com.hmx.webide.ai.errors

sealed class AiException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

class NetworkException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

class AuthenticationException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

class RateLimitException(
  message: String,
  /** Seconds the provider asked us to wait (Retry-After), if supplied. */
  val retryAfterSeconds: Long? = null,
  cause: Throwable? = null,
) : AiException(message, cause)

/** Account/project quota or balance exhausted — distinct from a temporary rate limit. */
class QuotaException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

class ModelNotFoundException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

class ProviderException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

class StreamInterruptedException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

/**
 * The AI generation exceeded the network read window. Distinct from a connectivity failure:
 * the request was in flight but the provider was too slow, so the task is surfaced as FAILED
 * with a Continue action rather than a silent generic "timeout".
 */
class GenerationTimeoutException(message: String, cause: Throwable? = null) :
  AiException(message, cause)

class ProviderConfigurationException(message: String, cause: Throwable? = null) :
  AiException(message, cause)
