package com.streamflixreborn.streamflix.fragments.providers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Provider as ModelProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ProviderEndpointRegistry
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class ProvidersViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = _state

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val items: List<AppAdapter.Item>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getProviders(UserPreferences.providerLanguage)
    }

    fun getProviders(language: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val remoteEntries = ProviderEndpointRegistry.refreshIfNeeded(Provider.providers.keys)
            val isFavoritesFilter = language == "favorites"
            val favorites = UserPreferences.favoriteProviders

            val providers = Provider.providers.keys
                .filter {
                    if (isFavoritesFilter) {
                        favorites.contains(it.name)
                    } else {
                        language == null || it.language == language
                    }
                }
                .sortedBy { it.name }
                .toMutableList()

            if (language == null || isFavoritesFilter) {
                val availableLanguages = Provider.providers.keys.map { it.language }.distinct()
                availableLanguages.forEach { lang ->
                    if (lang != "pl") {
                        val tmdbName = "TMDb (${getLanguageDisplayName(lang)})"
                        if (!isFavoritesFilter || favorites.contains(tmdbName)) {
                            providers.add(TmdbProvider(lang))
                        }
                    }
                }
            } else if (language != "pl") {
                providers.add(TmdbProvider(language))
            }

            val originalModels = providers.map {
                val name = if (it is TmdbProvider) {
                    "TMDb (${getLanguageDisplayName(it.language)})"
                } else {
                    it.name
                }
                ModelProvider(
                    name = name,
                    logo = it.logo,
                    language = it.language,
                    provider = it,
                    isFavorite = favorites.contains(name),
                    origin = ModelProvider.Origin.ORIGINAL,
                )
            }.sortedWith(
                compareBy<ModelProvider> { it.provider is TmdbProvider }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )

            val remoteModels = remoteEntries
                .filter { entry ->
                    !isFavoritesFilter && (language == null || entry.language == language)
                }
                .mapNotNull { entry ->
                    val engine = Provider.providers.keys.firstOrNull { it.name == entry.engineName }
                        ?: return@mapNotNull null
                    ModelProvider(
                        name = entry.displayName,
                        logo = entry.logo ?: engine.logo,
                        language = entry.language,
                        provider = engine,
                        origin = ModelProvider.Origin.PASTEBIN,
                        endpointOverride = entry.url,
                        endpointHealthy = entry.healthy,
                    )
                }
                .sortedBy { it.name.lowercase(Locale.ROOT) }

            val items = buildList<AppAdapter.Item> {
                if (originalModels.isNotEmpty()) {
                    add(ProviderSectionHeader("Provider originali"))
                    addAll(originalModels)
                }
                if (remoteModels.isNotEmpty()) {
                    add(ProviderSectionHeader("Provider Pastebin"))
                    addAll(remoteModels)
                }
            }

            _state.emit(State.SuccessLoading(items))
        } catch (e: Exception) {
            Log.e("ProvidersViewModel", "getProviders: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    private fun getLanguageDisplayName(languageCode: String): String =
        Locale.forLanguageTag(languageCode).displayLanguage
}
