package com.surveyme.presentation.notes

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.surveyme.data.PoiManager
import com.surveyme.databinding.FragmentNotesBinding
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
        NotesViewModelFactory(PoiManager.getRepository(requireContext()))
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
        observeViewModel()
        setupLocationUpdates()
        
        // Check if we should auto-open a specific note (from map marker click)
        checkForPendingNote()
    }

    private fun checkForPendingNote() {
        val note = pendingNote ?: return
        pendingNote = null
        
        val dialog = NoteDetailsDialogFragment.newInstance(
            noteId = note.first,
            title = note.second,
            description = note.third
        )
        dialog.show(childFragmentManager, NoteDetailsDialogFragment.TAG)
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter { noteItem ->
            showNoteDetails(noteItem)
        }
        binding.recyclerViewNotes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
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

    private fun setupLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            
            // Get last known location quickly
            val lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (lastLocation != null) {
                viewModel.updateLocation(lastLocation)
            }

            // Start updates
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    viewModel.updateLocation(location)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                // Request updates with fairly frequent intervals since we are walking
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // 5 seconds
                    2f, // 2 meters
                    locationListener!!,
                    Looper.getMainLooper()
                )
                
                // Also listen to network provider as fallback
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    2f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Timber.e(e, "Error requesting location updates")
            }
        } else {
            Timber.d("Location permission not granted for NotesFragment")
        }
    }

    private fun showNoteDetails(noteItem: NoteItem) {
        val dialog = NoteDetailsDialogFragment.newInstance(
            noteId = noteItem.poi.id,
            title = noteItem.poi.name,
            description = noteItem.poi.notes ?: "No details available"
        )
        dialog.show(childFragmentManager, NoteDetailsDialogFragment.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
        _binding = null
    }
}
