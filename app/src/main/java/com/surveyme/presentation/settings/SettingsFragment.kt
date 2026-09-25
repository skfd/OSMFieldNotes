package com.surveyme.presentation.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.surveyme.BuildConfig
import com.surveyme.R
import com.surveyme.SurveyMeApplication
import com.surveyme.core.PermissionManager
import com.surveyme.core.PreferencesManager
import com.surveyme.databinding.FragmentSettingsBinding
import com.surveyme.presentation.base.BaseFragment
import com.surveyme.presentation.debug.LogViewerDialog
import timber.log.Timber
import com.surveyme.data.PoiManager
import com.surveyme.data.NearbyFetcher
import com.surveyme.data.OsmAuthManager
import com.surveyme.data.OsmAuthState
import com.surveyme.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import com.surveyme.service.LocationTrackingService

class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var permissionManager: PermissionManager

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionStatus()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updatePermissionStatus()
    }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentSettingsBinding {
        return FragmentSettingsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Timber.d("SettingsFragment onViewCreated")

        preferencesManager = PreferencesManager(requireContext())
        permissionManager = PermissionManager(requireContext())

        setupSettings()
        setupOsmAccount()
        setupPermissions()
        setupPoiSection()
        setupDebugSection()
        updatePermissionStatus()
        updatePoiCount()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupSettings() {
        with(binding) {
            // Set current values
            switchDarkMode.isChecked = preferencesManager.isDarkMode
            switchNotifications.isChecked = preferencesManager.areNotificationsEnabled
            switchDrawTrack.isChecked = preferencesManager.isDrawTrackEnabled
            textMapStyle.text = preferencesManager.mapStyle.capitalize()
            textVersion.text = BuildConfig.VERSION_NAME

            // Set listeners
            switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
                preferencesManager.isDarkMode = isChecked
            }

            switchNotifications.setOnCheckedChangeListener { _, isChecked ->
                preferencesManager.areNotificationsEnabled = isChecked
            }

            switchDrawTrack.setOnCheckedChangeListener { _, isChecked ->
                preferencesManager.isDrawTrackEnabled = isChecked
            }
        }
    }

    private fun setupOsmAccount() {
        val auth = OsmAuthManager.get(requireContext())
        binding.buttonOsmLogin.setOnClickListener {
            when (auth.login(requireActivity())) {
                OsmAuthManager.LoginLaunch.Launched ->
                    Toast.makeText(context, R.string.osm_login_opening, Toast.LENGTH_SHORT).show()
                OsmAuthManager.LoginLaunch.NotConfigured ->
                    Toast.makeText(context, R.string.osm_login_not_configured, Toast.LENGTH_LONG).show()
                OsmAuthManager.LoginLaunch.NoBrowser ->
                    Toast.makeText(context, R.string.osm_login_no_browser, Toast.LENGTH_LONG).show()
            }
        }
        binding.buttonOsmLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                auth.logout()
                Toast.makeText(context, R.string.osm_logged_out_toast, Toast.LENGTH_SHORT).show()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                auth.state.collect { state ->
                    when (state) {
                        is OsmAuthState.LoggedIn -> {
                            binding.textOsmAccount.text = getString(R.string.osm_logged_in_as, state.displayName)
                            binding.buttonOsmLogin.visibility = View.GONE
                            binding.buttonOsmLogout.visibility = View.VISIBLE
                        }
                        OsmAuthState.LoggedOut -> {
                            binding.textOsmAccount.text = getString(R.string.osm_logged_out)
                            binding.buttonOsmLogin.visibility = View.VISIBLE
                            binding.buttonOsmLogout.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun setupPoiSection() {
        with(binding) {

            buttonClearPois.setOnClickListener {
                clearAllPois()
            }

            buttonImportGpx.setOnClickListener {
                Toast.makeText(context, "GPX import coming soon!", Toast.LENGTH_SHORT).show()
            }

            buttonFindOsmNotes.setOnClickListener {
                findOsmNotesAndFixmes()
            }
        }
    }

    private fun findOsmNotesAndFixmes() {
        binding.buttonFindOsmNotes.isEnabled = false
        binding.buttonFindOsmNotes.alpha = 0.5f

        // While tracking the walker's position is the centre, otherwise the last map position.
        // Fetching around a stale map position mid-walk would expire everything around the walker.
        val walker = if (LocationTrackingService.isTrackingFlow.value) LocationTrackingService.lastLocationFlow.value else null
        val lastLocation = preferencesManager.getLastMapPosition()
        val lat = walker?.latitude ?: lastLocation?.first ?: Constants.DEFAULT_MAP_CENTER_LAT
        val lon = walker?.longitude ?: lastLocation?.second ?: Constants.DEFAULT_MAP_CENTER_LON
        val where = if (walker != null) "your position" else "map position"
        val fetcher = PoiManager.getFetcher(requireContext())

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Searching for OSM Notes and FIXMEs around $where...", Toast.LENGTH_SHORT).show()
                }

                // Manual: skips the movement check and minimum interval, still honours a backoff.
                val report = fetcher.fetch(lat, lon, NearbyFetcher.Trigger.MANUAL)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, report.describe(), Toast.LENGTH_LONG).show()
                    if (report.touchedDatabase) updatePoiCount()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error searching: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Timber.e(e, "Error searching for OSM Notes and FIXMEs")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.buttonFindOsmNotes.isEnabled = true
                    binding.buttonFindOsmNotes.alpha = 1.0f
                }
            }
        }
    }

    private fun clearAllPois() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = PoiManager.getRepository(requireContext())
                repository.deleteAllFromSource("manual")
                repository.deleteAllFromSource("gpx_import")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "All POIs cleared!", Toast.LENGTH_SHORT).show()
                    updatePoiCount()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to clear POIs: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updatePoiCount() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = PoiManager.getRepository(requireContext())
                val stats = repository.getStats()
                withContext(Dispatchers.Main) {
                    binding.textPoiCount.text = "${stats.first} POIs (${stats.second} visited)"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.textPoiCount.text = "Error loading count"
                }
            }
        }
    }

    private fun setupPermissions() {
        with(binding) {
            layoutLocationPermission.setOnClickListener {
                if (!permissionManager.hasLocationPermission()) {
                    requestLocationPermissions()
                } else {
                    openAppSettings()
                }
            }

            layoutBackgroundPermission.setOnClickListener {
                if (!permissionManager.hasBackgroundLocationPermission()) {
                    requestBackgroundLocationPermission()
                } else {
                    openAppSettings()
                }
            }
        }
    }

    private fun updatePermissionStatus() {
        val hasLocation = permissionManager.hasLocationPermission()
        val hasBackground = permissionManager.hasBackgroundLocationPermission()

        with(binding) {
            // Update location permission status
            if (hasLocation) {
                textLocationStatus.text = getString(R.string.permission_granted)
                textLocationStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                )
            } else {
                textLocationStatus.text = getString(R.string.permission_denied)
                textLocationStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                )
            }

            // Update background permission status
            if (hasBackground) {
                textBackgroundStatus.text = getString(R.string.permission_granted)
                textBackgroundStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                )
            } else {
                textBackgroundStatus.text = getString(R.string.permission_denied)
                textBackgroundStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                )
            }
        }
    }

    private fun requestLocationPermissions() {
        if (permissionManager.shouldShowLocationPermissionRationale(requireActivity())) {
            permissionManager.showLocationPermissionRationale(requireActivity()) {
                permissionManager.requestLocationPermissions(this, locationPermissionLauncher)
            }
        } else {
            permissionManager.requestLocationPermissions(this, locationPermissionLauncher)
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (!permissionManager.hasLocationPermission()) {
            Toast.makeText(requireContext(), "Grant location permission first", Toast.LENGTH_SHORT).show()
            return
        }

        if (permissionManager.shouldShowBackgroundLocationRationale(requireActivity())) {
            permissionManager.showBackgroundLocationRationale(requireActivity()) {
                permissionManager.requestBackgroundLocationPermission(this, backgroundLocationLauncher)
            }
        } else {
            permissionManager.requestBackgroundLocationPermission(this, backgroundLocationLauncher)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    private fun setupDebugSection() {
        if (BuildConfig.DEBUG) {
            with(binding) {
                // Show debug section in debug builds
                textDebugTitle.visibility = View.VISIBLE
                cardDebug.visibility = View.VISIBLE

                buttonViewLogs.setOnClickListener {
                    val logFile = SurveyMeApplication.instance.getLogFile()
                    LogViewerDialog.newInstance(logFile, getString(R.string.logs_title))
                        .show(parentFragmentManager, "logs")
                }

                buttonViewCrashLogs.setOnClickListener {
                    val crashLogFile = SurveyMeApplication.instance.getCrashLogFile()
                    LogViewerDialog.newInstance(crashLogFile, getString(R.string.crash_logs_title))
                        .show(parentFragmentManager, "crash_logs")
                }

                buttonClearLogs.setOnClickListener {
                    try {
                        SurveyMeApplication.instance.getLogFile().writeText("")
                        SurveyMeApplication.instance.getCrashLogFile().writeText("")
                        Toast.makeText(requireContext(), getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                        Timber.d("Logs cleared by user")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to clear logs")
                        Toast.makeText(requireContext(), "Failed to clear logs", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}