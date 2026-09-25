package com.surveyme.presentation.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.surveyme.R
import com.surveyme.data.PoiManager
import com.surveyme.data.flattenThread
import com.surveyme.data.model.OsmNote
import com.surveyme.databinding.FragmentNoteDetailsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class NoteDetailsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentNoteDetailsBinding? = null
    private val binding get() = _binding!!
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoteDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val poiId = arguments?.getString(ARG_NOTE_ID)
        val title = arguments?.getString(ARG_TITLE) ?: "Note Details"
        val description = arguments?.getString(ARG_DESCRIPTION) ?: ""

        binding.textTitle.text = title
        binding.textComments.text = description

        // The structured note, when the row has one, replaces the flattened text that was passed
        // in. Item 4 renders this as real comments; for now it is a header plus the thread.
        if (poiId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val note = runCatching {
                    PoiManager.getRepository(requireContext()).getNoteByPoiId(poiId)
                }.getOrNull() ?: return@launch
                _binding?.textComments?.text = render(note)
            }
        }

        binding.buttonClose.setOnClickListener {
            dismiss()
        }

        binding.buttonBackToMap.setOnClickListener {
            dismiss()
            activity?.findViewById<BottomNavigationView>(R.id.bottomNavigation)
                ?.selectedItemId = R.id.mapFragment
        }
    }

    private fun render(note: OsmNote): String = buildString {
        append(note.status.apiValue.replaceFirstChar { it.uppercase() })
        append(" · created ${dateFormat.format(note.createdAt)}")
        note.closedAt?.let { append(" · closed ${dateFormat.format(it)}") }
        append(" · ${note.comments.size} ${if (note.comments.size == 1) "entry" else "entries"}")
        append("\n").append(note.url)
        append("\n\n")
        append(flattenThread(note).ifBlank { "No text." })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NoteDetailsDialogFragment"
        private const val ARG_NOTE_ID = "note_id"
        private const val ARG_TITLE = "title"
        private const val ARG_DESCRIPTION = "description"

        fun newInstance(noteId: String, title: String, description: String): NoteDetailsDialogFragment {
            return NoteDetailsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTE_ID, noteId)
                    putString(ARG_TITLE, title)
                    putString(ARG_DESCRIPTION, description)
                }
            }
        }
    }
}
