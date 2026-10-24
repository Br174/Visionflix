package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.utils.UserPreferences

object ProviderSelection {
    fun select(providerName: String?, endpoint: String?): Provider? {
        val targetName = providerName?.takeIf { it.isNotBlank() } ?: return null
        val target = Provider.providers.keys.firstOrNull { it.name == targetName } ?: return null

        if (endpoint.isNullOrBlank()) {
            ProviderEndpointRegistry.selectOriginal(target.name)
        } else {
            ProviderEndpointRegistry.selectRemote(target.name, endpoint)
        }
        (target as? EndpointManagedProvider)?.onEndpointSelectionChanged()

        if (UserPreferences.currentProvider?.name != target.name) {
            UserPreferences.currentProvider = target
        }

        return target
    }
}
