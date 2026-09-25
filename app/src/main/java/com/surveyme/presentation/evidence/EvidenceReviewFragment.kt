package com.surveyme.presentation.evidence

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
import com.surveyme.R
import com.surveyme.core.LastKnownLocation
import com.surveyme.data.PoiManager
import com.surveyme.data.model.Evidence
import com.surveyme.databinding.FragmentEvidenceReviewBinding
import com.surveyme.presentation.notes.NoteDetailsDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "To review" (item 11): the photos and text pinned in the field, newest first, for the desk
 * session. Reached from the Home card. Open goes to the details sheet while the POI is still
 * stored and to the snapshot once it expired; Share hands the photo or text to another app;
 * nothing here uploads anything.
 */
class EvidenceReviewFragment : Fragment() {

    private var _binding: FragmentEvidenceReviewBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EvidenceReviewViewModel by viewModels {
        EvidenceReviewViewModelFactory(PoiManager.getRepository(requireContext()))
    }

    private lateinit var adapter: EvidenceReviewAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEvidenceReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = EvidenceReviewAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onOpen = ::open,
            onToggleReviewed = { viewModel.setReviewed(it, !it.reviewed) },
            onShare = { EvidenceIntents.share(requireContext(), it) },
            onDelete = ::confirmDelete,
            onPhoto = { EvidenceIntents.view(requireContext(), it) }
        )
        adapter.here = LastKnownLocation.get(requireContext())
        binding.recyclerEvidence.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerEvidence.adapter = adapter

        binding.switchShowReviewed.isChecked = viewModel.showReviewed.value
        binding.switchShowReviewed.setOnCheckedChangeListener { _, checked -> viewModel.setShowReviewed(checked) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.state, viewModel.showReviewed) { state, showReviewed -> state to showReviewed }
                    .collect { (state, showReviewed) -> render(state, showReviewed) }
            }
        }
    }

    private fun render(state: EvidenceReviewViewModel.State, showReviewed: Boolean) {
        when (state) {
            EvidenceReviewViewModel.State.Loading -> {
                binding.textEmpty.visibility = View.GONE
                binding.textHeader.text = getString(R.string.loading)
            }
            is EvidenceReviewViewModel.State.Error -> {
                binding.textEmpty.visibility = View.VISIBLE
                binding.textEmpty.text = "Error: ${state.message}"
                binding.textHeader.text = ""
            }
            is EvidenceReviewViewModel.State.Ready -> {
                binding.textHeader.text = if (showReviewed) {
                    getString(R.string.evidence_header_all, state.total, state.unreviewed)
                } else {
                    getString(R.string.evidence_header_unreviewed, state.unreviewed)
                }
                binding.textEmpty.visibility = if (state.items.isEmpty()) View.VISIBLE else View.GONE
                binding.textEmpty.text = getString(
                    if (showReviewed) R.string.evidence_empty_all else R.string.evidence_empty_unreviewed
                )
                adapter.submitList(state.items)
            }
        }
    }

    /** The details sheet while the POI is stored; the snapshot once the fetch radius moved on. */
    private fun open(evidence: Evidence) {
        viewLifecycleOwner.lifecycleScope.launch {
            val poi = viewModel.findPoi(evidence)
            if (!isAdded) return@launch
            if (poi != null) {
                NoteDetailsDialogFragment.newInstance(
                    noteId = poi.id,
                    title = poi.name,
                    description = evidence.itemSummary
                ).show(childFragmentManager, NoteDetailsDialogFragment.TAG)
                return@launch
            }
            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle("${EvidenceIntents.itemLabel(evidence)}: ${evidence.itemTitle}")
                .setMessage(
                    buildString {
                        append(getString(R.string.evidence_item_gone))
                        if (evidence.itemSummary.isNotBlank()) append("\n\n").append(evidence.itemSummary)
                        evidence.text?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    }
                )
                .setPositiveButton(android.R.string.ok, null)
            if (evidence.isPhoto) {
                builder.setNeutralButton(R.string.evidence_open_photo) { _, _ -> EvidenceIntents.view(requireContext(), evidence) }
            }
            builder.show()
        }
    }

    private fun confirmDelete(evidence: Evidence) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.evidence_delete_title)
            .setMessage(R.string.evidence_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_action_delete) { _, _ -> viewModel.delete(evidence) }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerEvidence.adapter = null
        _binding = null
    }
}
