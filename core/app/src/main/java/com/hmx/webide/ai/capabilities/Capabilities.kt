package com.hmx.webide.ai.capabilities

import com.hmx.webide.ai.AiProvider
import com.hmx.webide.ai.models.Capability
import com.hmx.webide.ai.registry.ProviderRegistry

object Capabilities {

  fun providersWith(capability: Capability): List<AiProvider> =
    ProviderRegistry.all().filter { it.supports(capability) }

  fun activeSupports(capability: Capability): Boolean =
    ProviderRegistry.active()?.supports(capability) ?: false
}

fun AiProvider.supports(capability: Capability): Boolean = capability in capabilities
