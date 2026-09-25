package com.surveyme.presentation.muted

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.surveyme.R
import com.surveyme.core.LastKnownLocation
import com.surveyme.data.PoiManager
import com.surveyme.data.model.Poi
import com.surveyme.databinding.FragmentMutedItemsBinding
import com.surveyme.presentation.notes.NoteDetailsDialogFragment
import kotlinx.coroutines.launch

/**
 * "Muted items" (item 13), reached from the Settings row: the notes and fixmes whose
 * `isMuted` flag is set, each with an Unmute button and an Unmute all up top. A tap opens the
 * details sheet, whose own chips can mute or unmute too. Placeholders for queued creates are
 * muted by design and are not listed.
 */
class MutedItemsFragment : Fragment() {

    private var _binding: FragmentMutedItemsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MutedItemsViewModel by viewModels {
        MutedItemsViewModelFactory(PoiManager.getRepository(requireContext()))
    }

    private lateinit var adapter: MutedItemsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMutedItemsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MutedItemsAdapter(onOpen = ::open, onUnmute = ::unmute)
        adapter.here = LastKnownLocation.get(requireContext())
        binding.recyclerMuted.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMuted.adapter = adapter

        binding.buttonUnmuteAll.setOnClickListener { confirmUnmuteAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: MutedItemsViewModel.State) {
        when (state) {
            MutedItemsViewModel.State.Loading -> {
                binding.textEmpty.visibility = View.GONE
                binding.textHeader.text = getString(R.string.loading)
                binding.buttonUnmuteAll.isEnabled = false
            }
            is MutedItemsViewModel.State.Error -> {
                binding.textEmpty.visibility = View.VISIBLE
                binding.textEmpty.text = "Error: ${state.message}"
                binding.textHeader.text = ""
                binding.buttonUnmuteAll.isEnabled = false
            }
            is MutedItemsViewModel.State.Ready -> {
                val notes = state.items.count { !it.poi.isFixme }
                val fixmes = state.items.size - notes
                binding.textHeader.text = getString(R.string.muted_header, state.items.size, notes, fixmes)
                binding.textEmpty.visibility = if (state.items.isEmpty()) View.VISIBLE else View.GONE
                binding.textEmpty.text = getString(R.string.muted_empty)
                binding.buttonUnmuteAll.isEnabled = state.items.isNotEmpty()
                adapter.submitList(state.items)
            }
        }
    }

    private fun open(poi: Poi) {
        NoteDetailsDialogFragment.newInstance(
            noteId = poi.id,
            title = poi.name,
            description = poi.description ?: ""
        ).show(childFragmentManager, NoteDetailsDialogFragment.TAG)
    }

    /** The row leaves the list on the next emission; Undo puts the flag back. */
    private fun unmute(poi: Poi) {
        viewModel.setMuted(poi.id, false)
        Snackbar.make(binding.root, getString(R.string.muted_unmuted_one), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { viewModel.setMuted(poi.id, true) }
            .show()
    }

    private fun confirmUnmuteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.muted_unmute_all)
            .setMessage(R.string.muted_unmute_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.muted_unmute_all) { _, _ ->
                viewModel.unmuteAll { count ->
                    if (!isAdded) return@unmuteAll
                    Snackbar.make(binding.root, getString(R.string.muted_unmuted_many, count), Snackbar.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerMuted.adapter = null
        _binding = null
    }
}
