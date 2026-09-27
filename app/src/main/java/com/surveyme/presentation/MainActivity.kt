package com.surveyme.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.surveyme.R
import com.surveyme.data.OsmAuthManager
import com.surveyme.data.PoiManager
import com.surveyme.databinding.ActivityMainBinding
import com.surveyme.presentation.notes.NotesFragment
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    companion object {
        /** POI id to open in the Notes tab; set by the proximity notification. */
        const val EXTRA_OPEN_POI_ID = "com.surveyme.OPEN_POI_ID"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Timber.d("MainActivity onCreate")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        if (savedInstanceState == null) {
            handleOpenPoiIntent(intent)
            handleOAuthCallback(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenPoiIntent(intent)
        handleOAuthCallback(intent)
    }

    /**
     * `osmfieldnotes://oauth/callback?code=...&state=...` from the Custom Tab (onNewIntent while
     * the app is alive, onCreate when it was killed behind the browser). The data is nulled once
     * consumed so a rotation does not replay the exchange.
     */
    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!OsmAuthManager.isCallback(uri)) return
        intent.data = null
        Timber.d("OSM OAuth callback received")
        lifecycleScope.launch {
            OsmAuthManager.get(this@MainActivity).handleCallback(uri)
                .onSuccess {
                    Toast.makeText(this@MainActivity, getString(R.string.osm_login_success, it.displayName), Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, getString(R.string.osm_login_failed, it.message), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun handleOpenPoiIntent(intent: Intent?) {
        val poiId = intent?.getStringExtra(EXTRA_OPEN_POI_ID) ?: return
        intent.removeExtra(EXTRA_OPEN_POI_ID)
        Timber.d("Deep link to POI $poiId")
        lifecycleScope.launch {
            val poi = PoiManager.getRepository(this@MainActivity).getPoiById(poiId)
            if (poi == null) {
                Timber.w("Deep-linked POI $poiId no longer exists")
                return@launch
            }
            NotesFragment.pendingNote = Triple(
                poi.id,
                poi.name,
                poi.notes ?: poi.description ?: ""
            )
            val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val current = navHost.childFragmentManager.primaryNavigationFragment
            if (current is NotesFragment) {
                current.checkForPendingNote()
            } else {
                binding.bottomNavigation.selectedItemId = R.id.notesFragment
            }
        }
    }

    private fun setupNavigation() {
        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Define top-level destinations (no back button)
        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.mapFragment,
            R.id.tracksFragment,
            R.id.settingsFragment,
            R.id.notesFragment
        )
        appBarConfiguration = AppBarConfiguration(topLevelDestinations)

        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNavigation.setupWithNavController(navController)
    }

    override fun onSupportNavigateUp(): Boolean {
        return NavigationUI.navigateUp(navController, appBarConfiguration) || super.onSupportNavigateUp()
    }
}