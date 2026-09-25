package com.surveyme.presentation.notes

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.navigation.fragment.findNavController
import com.surveyme.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.surveyme.core.FixmeMaxAge
import com.surveyme.core.NoteLabels
import com.surveyme.core.PreferencesManager
import com.surveyme.data.PoiManager
import com.surveyme.databinding.FragmentNotesBinding
import com.surveyme.service.LocationTrackingService
import kotlinx.coroutines.launch
import timber.log.Timber
import android.location.LocationListener
import android.location.LocationManager

import com.surveyme.presentation.notes.NotesFragment.Companion.pendingNote

class NotesFragment : Fragment() {

    companion object {
        /** Set by MapFragment to auto-open a note on tab switch. Triple(id, title, description) */
        var pendingNote: Triple<String, String, String>? = null
    }

    private var _binding: FragmentNotesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(PoiManager.getRepository(requireContext()), PreferencesManager(requireContext()))
    }

    private lateinit var notesAdapter: NotesAdapter
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupFilterChips()
        setupMenu()
        observeViewModel()
        seedLocation()
        observeServiceLocation()
        
        // Check if we should auto-open a specific note (from map marker click)
        checkForPendingNote()
    }

    /** Opens the note left in [pendingNote], if any. Called on view creation and by MainActivity on a deep link. */
    fun checkForPendingNote() {
        val note = pendingNote ?: return
        pendingNote = null
        
        val dialog = NoteDetailsDialogFragment.newInstance(
            noteId = note.first,
            title = note.second,
            description = note.third
        )
        dialog.show(childFragmentManager, NoteDetailsDialogFragment.TAG)
    }

    /** Menu ids of the fixme age choices (item 12), in [FixmeMaxAge] terms. */
    private val ageMenuItems = mapOf(
        R.id.action_fixme_age_any to FixmeMaxAge.ANY,
        R.id.action_fixme_age_1y to FixmeMaxAge.ONE_YEAR,
        R.id.action_fixme_age_2y to FixmeMaxAge.TWO_YEARS,
        R.id.action_fixme_age_5y to FixmeMaxAge.FIVE_YEARS
    )

    /**
     * Item 9: a "Triage" action in the toolbar while this tab is showing; item 12: a "Fixme age"
     * submenu in the overflow, its radio state read from the ViewModel each time it opens.
     */
    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_notes, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                val current = viewModel.fixmeMaxAge.value
                ageMenuItems.forEach { (id, age) -> menu.findItem(id)?.isChecked = age == current }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                ageMenuItems[menuItem.itemId]?.let { age ->
                    viewModel.setFixmeMaxAge(age)
                    menuItem.isChecked = true
                    requireActivity().invalidateMenu()
                    return true
                }
                if (menuItem.itemId != R.id.action_triage) return false
                findNavController().navigate(R.id.triageFragment)
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter { noteItem ->
            showNoteDetails(noteItem)
        }
        binding.recyclerViewNotes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
        }
        ItemTouchHelper(SwipeToDismissFixme()).attachToRecyclerView(binding.recyclerViewNotes)
    }

    /**
     * Item 12: swiping a fixme row either way dismisses it for now (or takes a dismissal back),
     * with an undo in a snackbar. Notes do not swipe. The row is not removed, it drops to the
     * bottom when the database emits, so the swiped-out view is snapped back at once.
     */
    private inner class SwipeToDismissFixme : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val position = viewHolder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return 0
            val item = notesAdapter.currentList.getOrNull(position) ?: return 0
            return if (item.poi.isFixme) super.getSwipeDirs(recyclerView, viewHolder) else 0
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val item = notesAdapter.currentList.getOrNull(position) ?: return
            notesAdapter.notifyItemChanged(position)
            val dismiss = !item.poi.isMuted
            viewModel.setFixmeDismissed(item.poi.id, dismiss)
            Snackbar.make(
                binding.root,
                if (dismiss) "Fixme dismissed for now: no pings, bottom of the list" else "Fixme restored: pings again",
                Snackbar.LENGTH_LONG
            )
                .setAnchorView(activity?.findViewById<BottomNavigationView>(R.id.bottomNavigation))
                .setAction("Undo") { viewModel.setFixmeDismissed(item.poi.id, !dismiss) }
                .show()
        }
    }

    /**
     * The audience chips (item 8). The checked chip follows the ViewModel's filter, which is
     * seeded from prefs, so a restart shows the list and the chip in agreement.
     */
    private fun setupFilterChips() {
        val chipFor = mapOf(
            PreferencesManager.AUDIENCE_FILTER_ALL to binding.chipAll.id,
            NoteLabels.SURVEYOR to binding.chipSurveyor.id,
            NoteLabels.TELEMAPPER to binding.chipTelemapper.id,
            NoteLabels.JOSM to binding.chipJosm.id
        )
        binding.chipGroupAudience.check(chipFor[viewModel.filter.value] ?: binding.chipAll.id)
        binding.chipGroupAudience.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val filter = chipFor.entries.firstOrNull { it.value == id }?.key ?: PreferencesManager.AUDIENCE_FILTER_ALL
            viewModel.setFilter(filter)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notesState.collect { state ->
                    when (state) {
                        is NotesViewModel.NotesState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.recyclerViewNotes.visibility = View.GONE
                            binding.textEmpty.visibility = View.GONE
                        }
                        is NotesViewModel.NotesState.Empty -> {
                            binding.progressBar.visibility = View.GONE
                            binding.recyclerViewNotes.visibility = View.GONE
                            binding.textEmpty.visibility = View.VISIBLE
                            binding.textEmpty.text = if (state.filtered) {
                                "Nothing matches the filters.\nTry another chip, or a longer fixme age in the menu."
                            } else {
                                "No OSM Notes found.\nGo to Settings and tap 'Find OSM Notes' to fetch nearby notes."
                            }
                        }
                        is NotesViewModel.NotesState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.recyclerViewNotes.visibility = View.VISIBLE
                            binding.textEmpty.visibility = View.GONE
                            
                            val isAtTop = !binding.recyclerViewNotes.canScrollVertically(-1)
                            notesAdapter.submitList(state.notes) {
                                if (isAtTop && state.notes.isNotEmpty()) {
                                    binding.recyclerViewNotes.scrollToPosition(0)
                                }
                            }
                        }
                        is NotesViewModel.NotesState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.recyclerViewNotes.visibility = View.GONE
                            binding.textEmpty.visibility = View.VISIBLE
                            binding.textEmpty.text = "Error: ${state.message}"
                        }
                    }
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The platform's last known fix, so the distances are right before the first update. */
    private fun seedLocation() {
        if (!hasLocationPermission()) {
            Timber.d("Location permission not granted for NotesFragment")
            return
        }
        locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (lastLocation != null) viewModel.updateLocation(lastLocation)
    }

    /**
     * While the tracking service runs, its fixes feed the list; this tab then holds no GPS
     * client of its own (battery pass, item 14). The listener below only runs when the service
     * does not, and only between onResume and onPause: before this it was removed in
     * onDestroyView, so the Notes tab kept the GPS at 5 s / 2 m while the app sat in the background.
     */
    private fun observeServiceLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocationTrackingService.lastLocationFlow.collect { location ->
                    if (location != null) viewModel.updateLocation(location)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                LocationTrackingService.isTrackingFlow.collect { tracking ->
                    if (tracking) stopOwnLocationUpdates() else startOwnLocationUpdates()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopOwnLocationUpdates()
    }

    private fun startOwnLocationUpdates() {
        if (locationListener != null || !hasLocationPermission()) return
        val manager = locationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                viewModel.updateLocation(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            // On screen and not tracking: a fix every 10 s / 5 m is plenty for a distance column.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 5f, listener, Looper.getMainLooper())
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 5f, listener, Looper.getMainLooper())
            locationListener = listener
            Timber.d("NotesFragment: own location updates started")
        } catch (e: Exception) {
            Timber.e(e, "Error requesting location updates")
        }
    }

    private fun stopOwnLocationUpdates() {
        val listener = locationListener ?: return
        locationManager?.removeUpdates(listener)
        locationListener = null
        Timber.d("NotesFragment: own location updates stopped")
    }

    private fun showNoteDetails(noteItem: NoteItem) {
        val dialog = NoteDetailsDialogFragment.newInstance(
            noteId = noteItem.poi.id,
            title = noteItem.poi.name,
            description = noteItem.poi.notes ?: noteItem.poi.description ?: "No details available",
            distanceMeters = noteItem.distance
        )
        dialog.show(childFragmentManager, NoteDetailsDialogFragment.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopOwnLocationUpdates()
        _binding = null
    }
}
