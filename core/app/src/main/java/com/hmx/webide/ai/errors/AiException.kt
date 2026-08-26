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

class ProviderConfigurationException(message: String, cause: Throwable? = null) :
  AiException(message, cause)
