package com.hmx.webide.ai

import android.content.Context
import com.hmx.webide.ai.engine.AiEngine
import com.hmx.webide.ai.providers.ProviderFactory
import com.hmx.webide.ai.storage.ProviderStorage

object AiFactory {

  private var engine: AiEngine? = null
  private var storage: ProviderStorage? = null

  fun init(context: Context) {
    storage = ProviderStorage(context)
    ProviderFactory.registerAll(storage)
    engine = AiEngine(storage)
  }

  fun engine(): AiEngine =
    engine ?: throw IllegalStateException("AiFactory.init() must be called first")

  fun storage(): ProviderStorage =
    storage ?: throw IllegalStateException("AiFactory.init() must be called first")
}
