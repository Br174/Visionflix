package com.streamflixreborn.streamflix.adapters.viewholders

import android.content.Intent
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.Toast
import android.widget.PopupMenu
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemProviderMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemProviderTvBinding
import com.streamflixreborn.streamflix.models.Provider
import com.streamflixreborn.streamflix.providers.EndpointManagedProvider
import com.streamflixreborn.streamflix.providers.Provider as EngineProvider
import com.streamflixreborn.streamflix.providers.ProviderEndpointRegistry
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var provider: Provider

    fun bind(provider: Provider) {
        this.provider = provider

        when (_binding) {
            is ItemProviderMobileBinding -> displayMobileItem(_binding)
            is ItemProviderTvBinding -> displayTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemProviderMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                val endpointOverride = provider.endpointOverride
                if (provider.origin == Provider.Origin.PASTEBIN && !provider.endpointHealthy) {
                    Toast.makeText(context, "Provider Pastebin non disponibile", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (provider.origin == Provider.Origin.PASTEBIN && !endpointOverride.isNullOrBlank()) {
                    ProviderEndpointRegistry.selectRemote(provider.provider.name, endpointOverride)
                } else {
                    ProviderEndpointRegistry.selectOriginal(provider.provider.name)
                }
                (provider.provider as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                UserPreferences.currentProvider = provider.provider
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                showProviderActions(binding.root)
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name + "  ⟳"
        binding.root.contentDescription = "${provider.name}. Tieni premuto per Aggiorna provider, Aggiorna tutti o Preferiti"

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }

    private fun displayTvItem(binding: ItemProviderTvBinding) {
        binding.root.apply {
            setOnClickListener {
                val endpointOverride = provider.endpointOverride
                if (provider.origin == Provider.Origin.PASTEBIN && !provider.endpointHealthy) {
                    Toast.makeText(context, "Provider Pastebin non disponibile", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (provider.origin == Provider.Origin.PASTEBIN && !endpointOverride.isNullOrBlank()) {
                    ProviderEndpointRegistry.selectRemote(provider.provider.name, endpointOverride)
                } else {
                    ProviderEndpointRegistry.selectOriginal(provider.provider.name)
                }
                (provider.provider as? EndpointManagedProvider)?.onEndpointSelectionChanged()
                UserPreferences.currentProvider = provider.provider
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                showProviderActions(binding.root)
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name + "  ⟳"
        binding.root.contentDescription = "${provider.name}. Tieni premuto per Aggiorna provider, Aggiorna tutti o Preferiti"

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }

    private fun showProviderActions(anchor: View) {
        val item = provider
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, "Aggiorna provider")
            menu.add(0, 2, 1, "Aggiorna tutti")
            menu.add(0, 3, 2, if (item.isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        Toast.makeText(context, "Controllo ${item.name} in corso", Toast.LENGTH_SHORT).show()
                        val owner = context.toActivity() as? LifecycleOwner
                        val scope = owner?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
                        scope.launch {
                            val result = runCatching {
                                ProviderEndpointRegistry.refreshOne(item.provider, requestedEndpoint = item.endpointOverride)
                            }.getOrElse { error ->
                                ProviderEndpointRegistry.RefreshResult(item.provider.name, false, error.message ?: "Errore sconosciuto")
                            }
                            android.app.AlertDialog.Builder(context)
                                .setTitle("Verifica ${item.name}")
                                .setMessage("${if (result.success) "Funzionante" else "Non funzionante"}: ${result.message}")
                                .setPositiveButton("Chiudi", null)
                                .show()
                        }
                        true
                    }
                    2 -> {
                        Toast.makeText(context, "Aggiornamento provider avviato; massimo 3 verifiche simultanee", Toast.LENGTH_LONG).show()
                        val owner = context.toActivity() as? LifecycleOwner
                        val scope = owner?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
                        scope.launch {
                            val results = runCatching { ProviderEndpointRegistry.refreshAll(EngineProvider.providers.keys) }
                                .getOrElse { error ->
                                    android.app.AlertDialog.Builder(context)
                                        .setTitle("Aggiornamento non completato")
                                        .setMessage(error.message ?: "Errore durante la verifica")
                                        .setPositiveButton("Chiudi", null).show()
                                    emptyList()
                                }
                            if (results.isNotEmpty()) {
                                val valid = results.count { it.success }
                                val details = results.joinToString("\n") { r ->
                                    "${if (r.success) "✓" else "✗"} ${r.providerName}: ${r.message}"
                                }
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("Provider verificati: $valid/${results.size}")
                                    .setMessage(details)
                                    .setPositiveButton("Chiudi", null)
                                    .show()
                            }
                        }
                        true
                    }
                    3 -> {
                        toggleFavorite(item)
                        val favorite = item.isFavorite
                        when (_binding) {
                            is ItemProviderMobileBinding -> _binding.ivProviderFavorite.visibility = if (favorite) View.VISIBLE else View.GONE
                            is ItemProviderTvBinding -> _binding.ivProviderFavorite.visibility = if (favorite) View.VISIBLE else View.GONE
                        }
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun loadProviderLogo(imageView: ImageView) {
        val logo = provider.logo.takeIf { it.isNotEmpty() }
        val isSvg = logo?.substringBefore("?")?.endsWith(".svg", ignoreCase = true) == true

        if (isSvg) {
            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            Glide.with(context)
                .`as`(PictureDrawable::class.java)
                .load(logo)
                .error(R.drawable.ic_provider_default_logo)
                .into(imageView)
        } else {
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            Glide.with(context)
                .load(logo ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView)
        }
    }
    
    private fun toggleFavorite(provider: Provider) {
        provider.isFavorite = !provider.isFavorite
        val favorites = UserPreferences.favoriteProviders.toMutableSet()
        if (provider.isFavorite) {
            favorites.add(provider.name)
        } else {
            favorites.remove(provider.name)
        }
        UserPreferences.favoriteProviders = favorites
    }
}
