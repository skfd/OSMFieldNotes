package com.surveyme.presentation.triage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.surveyme.R
import com.surveyme.core.NoteTriage
import com.surveyme.core.PreferencesManager
import com.surveyme.data.NoteActionQueue
import com.surveyme.data.OsmAuthManager
import com.surveyme.data.PoiManager
import com.surveyme.databinding.FragmentTriageBinding
import com.surveyme.presentation.notes.NoteDetailsDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Morning triage (item 9): the notes in the home area that are new since the last triage,
 * newest first, each with the heuristic guess pre-highlighted and one-tap buttons that post
 * the strict tags as comments. Reached from the Home card and the Notes tab's toolbar action.
 */
class TriageFragment : Fragment() {

    private var _binding: FragmentTriageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TriageViewModel by viewModels {
        val context = requireContext()
        TriageViewModelFactory(
            PoiManager.getRepository(context),
            PreferencesManager(context),
            NoteActionQueue.get(context)
        )
    }

    private lateinit var adapter: TriageAdapter
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTriageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TriageAdapter(
            onOpen = ::openDetails,
            onTag = ::tag,
            onSkip = { viewModel.skip(it.note.noteId) },
            onChange = { viewModel.change(it.note.noteId) }
        )
        binding.recyclerTriage.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTriage.adapter = adapter
        ItemTouchHelper(SwipeToSkip()).attachToRecyclerView(binding.recyclerTriage)

        binding.switchShowAll.isChecked = viewModel.showAll.value
        // Before the first Done everything is new anyway; the toggle has nothing to add.
        binding.switchShowAll.isEnabled = viewModel.lastTriageAt != NoteTriage.NEVER
        binding.switchShowAll.setOnCheckedChangeListener { _, checked -> viewModel.setShowAll(checked) }

        binding.buttonDone.setOnClickListener {
            viewModel.done()
            findNavController().navigateUp()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.state, viewModel.showAll) { state, showAll -> state to showAll }
                    .collect { (state, showAll) -> render(state, showAll) }
            }
        }
    }

    private fun render(state: TriageViewModel.State, showAll: Boolean) {
        when (state) {
            TriageViewModel.State.Loading -> {
                binding.textEmpty.visibility = View.GONE
                binding.textHeader.text = getString(R.string.loading)
            }
            is TriageViewModel.State.Error -> {
                binding.textEmpty.visibility = View.VISIBLE
                binding.textEmpty.text = "Error: ${state.message}"
                binding.textHeader.text = ""
            }
            is TriageViewModel.State.Ready -> {
                val items = state.items
                binding.textHeader.text = when {
                    viewModel.lastTriageAt == NoteTriage.NEVER -> getString(R.string.triage_header_never)
                    showAll -> getString(R.string.triage_header_all, items.size)
                    else -> getString(R.string.triage_header_new, items.size, dateFormat.format(Date(viewModel.lastTriageAt)))
                }
                binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.textEmpty.text = getString(
                    if (showAll || viewModel.lastTriageAt == NoteTriage.NEVER) R.string.triage_empty_all else R.string.triage_empty_new
                )
                adapter.submitList(items)
            }
        }
    }

    private fun openDetails(item: TriageItem) {
        NoteDetailsDialogFragment.newInstance(
            noteId = item.poi.id,
            title = item.poi.name,
            description = item.note.openingText
        ).show(childFragmentManager, NoteDetailsDialogFragment.TAG)
    }

    /**
     * A tag chip: needs a login (the queue would only hold the row otherwise, and a tag posted
     * anonymously is not what a triage is for), and `#surveyafter` needs a date first.
     */
    private fun tag(item: TriageItem, tag: String) {
        if (!OsmAuthManager.get(requireContext()).isLoggedIn) {
            promptLogin()
            return
        }
        if (tag == NoteTriage.TAG_SURVEYAFTER) {
            pickSurveyAfterDate(item)
            return
        }
        confirmQueued(item, viewModel.post(item.note.noteId, tag))
    }

    private fun pickSurveyAfterDate(item: TriageItem) {
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.triage_surveyafter_title)
            .setSelection(item.note.surveyAfter?.time?.takeIf { it > today } ?: today)
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { utcMidnight ->
            // The picker answers UTC midnight of the chosen day; the tag is written as that ISO day.
            confirmQueued(item, viewModel.post(item.note.noteId, NoteTriage.TAG_SURVEYAFTER, Date(utcMidnight)))
        }
        picker.show(childFragmentManager, "surveyafter")
    }

    private fun confirmQueued(item: TriageItem, text: String) {
        Timber.d("Triage queued '$text' on note ${item.note.noteId}")
        Toast.makeText(context, getString(R.string.triage_queued, text, item.note.noteId), Toast.LENGTH_SHORT).show()
    }

    private fun promptLogin() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.triage_login_title)
            .setMessage(R.string.triage_login_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.triage_login_open_settings) { _, _ ->
                activity?.findViewById<BottomNavigationView>(R.id.bottomNavigation)?.selectedItemId = R.id.settingsFragment
            }
            .show()
    }

    /** Swiping a row either way skips it for this session, same as the Skip chip. */
    private inner class SwipeToSkip : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return
            viewModel.skip(adapter.currentList[position].note.noteId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerTriage.adapter = null
        _binding = null
    }
}
