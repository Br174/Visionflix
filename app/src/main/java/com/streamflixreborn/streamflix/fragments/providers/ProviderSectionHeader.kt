package com.streamflixreborn.streamflix.fragments.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter

data class ProviderSectionHeader(
    val title: String,
) : AppAdapter.Item {
    override var itemType: AppAdapter.Type = AppAdapter.Type.PROVIDER_SECTION_HEADER
}
