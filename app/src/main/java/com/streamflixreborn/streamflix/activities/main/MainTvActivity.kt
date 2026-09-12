package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ActivityMainTvBinding
import com.streamflixreborn.streamflix.databinding.ContentHeaderMenuMainTvBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerTvFragment
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.providers.FilmyOnlineCcProvider
import com.streamflixreborn.streamflix.providers.GuardaSerieProvider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ZaluknijProvider
import com.streamflixreborn.streamflix.ui.UpdateAppTvDialog
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import kotlinx.coroutines.launch

class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()
    private lateinit var updateAppDialog: UpdateAppTvDialog

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))

        super.onCreate(savedInstanceState)

        AnimeOnlineNinjaProvider.init(this)
        Cine24hProvider.init(this)
        FilmyOnlineCcProvider.init(this)
        ZaluknijProvider.init(this)
        GuardaSerieProvider.init(this)

        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enforceTvFullscreen()
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                binding.ivSplashOverlay.visibility = View.GONE
            }

        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        val navController = navHostFragment.navController

        // Nessun overscan/padding artificiale: il contenuto usa l'intero pannello TV.
        adjustLayoutDelta(0, 0)

        if (
            BuildConfig.APP_LAYOUT == "mobile" ||
            (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
                navController.navigate(R.id.home)
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.navMain.headerView?.apply {
                val header = ContentHeaderMenuMainTvBinding.bind(this)

                Glide.with(context)
                    .load(
                        UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                            ?: R.drawable.ic_provider_default_logo
                    )
                    .error(R.drawable.ic_provider_default_logo)
                    .into(header.ivNavigationHeaderIcon)
                header.tvNavigationHeaderTitle.text = UserPreferences.currentProvider?.name
                header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_change_provider)

                val palette = ThemeManager.palette(UserPreferences.selectedTheme)
                header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
                header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)

                // Quando il menu è chiuso non deve esistere una fascia laterale opaca.
                setBackgroundColor(Color.TRANSPARENT)
                setOnOpenListener {
                    setBackgroundColor(palette.tvNavBackground)
                    header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                    header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                }
                setOnCloseListener {
                    setBackgroundColor(Color.TRANSPARENT)
                    header.tvNavigationHeaderTitle.visibility = View.GONE
                    header.tvNavigationHeaderSubtitle.visibility = View.GONE
                }

                setOnClickListener {
                    navController.navigate(R.id.providers)
                }
            }

            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> binding.navMain.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) {
                                    viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                                }
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> {
                        if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    }
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> {
                        if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    }
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(
                            this@MainTvActivity,
                            state.error.message ?: "Update failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    else -> Unit
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> {
                        if (binding.navMain.hasFocus()) finish() else binding.navMain.requestFocus()
                    }
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows, R.id.favorites -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled =
                            (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        enforceTvFullscreen()
        viewModel.checkUpdate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enforceTvFullscreen()
    }

    @Suppress("DEPRECATION")
    private fun enforceTvFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setPadding(0, 0, 0, 0)
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        // Il menu TV resta sovrapposto al contenuto, senza creare una colonna laterale.
        binding.navMain.setBackgroundColor(Color.TRANSPARENT)
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(Color.TRANSPARENT)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }

    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (provider is IptvProvider) {
                getString(R.string.main_menu_all_channels)
            } else {
                getString(R.string.main_menu_tv_shows)
            }
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        // I parametri restano per compatibilità con le impostazioni già salvate,
        // ma non devono più ridurre il canvas TV.
        binding.root.setPadding(0, 0, 0, 0)
        binding.navMainFragment.setPadding(0, 0, 0, 0)
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }
}
