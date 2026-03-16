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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.surveyme.data.PoiManager
import com.surveyme.databinding.FragmentNotesBinding
import kotlinx.coroutines.launch
import timber.log.Timber

class NotesFragment : Fragment() {

    private var _binding: FragmentNotesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(PoiManager.getRepository(requireContext()))
    }

    private lateinit var notesAdapter: NotesAdapter
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

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
                            notesAdapter.submitList(state.notes)
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
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            
            // Get last known location quickly
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    viewModel.updateLocation(location)
                }
            }

            // Start updates
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        viewModel.updateLocation(location)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
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
        if (::fusedLocationClient.isInitialized && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback!!)
        }
        _binding = null
    }
}
