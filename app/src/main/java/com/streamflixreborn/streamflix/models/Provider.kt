package com.streamflixreborn.streamflix.models

import com.streamflixreborn.streamflix.adapters.AppAdapter

open class Provider(
    val name: String,
    val logo: String,
    val language: String,

    val provider: com.streamflixreborn.streamflix.providers.Provider,
    var isFavorite: Boolean = false,
    val origin: Origin = Origin.ORIGINAL,
    val endpointOverride: String? = null,
    val endpointHealthy: Boolean = true,
) : AppAdapter.Item {

    enum class Origin { ORIGINAL, PASTEBIN }


    override lateinit var itemType: AppAdapter.Type
}