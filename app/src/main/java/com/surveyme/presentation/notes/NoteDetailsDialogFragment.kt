package com.surveyme.presentation.notes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.surveyme.R
import com.surveyme.data.NoteActionQueue
import com.surveyme.data.OsmAuthManager
import com.surveyme.data.OsmAuthState
import com.surveyme.data.PoiManager
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteActionKind
import com.surveyme.data.model.NoteActionRequest
import com.surveyme.data.model.NoteComment
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.databinding.DialogNoteActionBinding
import com.surveyme.databinding.FragmentNoteDetailsBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-height bottom sheet for one note or fixme. Opened from the Notes list, from a map marker
 * and from the proximity ping (all through [NotesFragment.pendingNote] / [newInstance]).
 *
 * Notes render their thread as comment rows from the `osm_notes` row, followed by the queued
 * comment / close / reopen requests of [NoteActionQueue] as "pending" rows; fixmes render the
 * fixme text and their raw tags. The note row and the queue are observed, so a post going
 * through (or being refused and reverted) re-renders the sheet while it is open. The title and
 * description arguments are the fallback for a POI that has no note row (pre-v4 data) or that
 * was expired between the ping and the tap.
 */
class NoteDetailsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentNoteDetailsBinding? = null
    private val binding get() = _binding!!
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val rowsAdapter = NoteDetailsAdapter()

    /** Set when the osm.org page for this item is known; null hides the button. */
    private var osmUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoteDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // A thread can be long: take the whole screen and let the RecyclerView scroll inside.
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        sheetDialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val poiId = arguments?.getString(ARG_POI_ID)
        val title = arguments?.getString(ARG_TITLE) ?: "Note Details"
        val description = arguments?.getString(ARG_DESCRIPTION) ?: ""
        val distanceArg = arguments?.getFloat(ARG_DISTANCE, Float.NaN) ?: Float.NaN

        binding.recyclerRows.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRows.adapter = rowsAdapter

        // What we know before the database answers: the text the caller passed in.
        binding.textTitle.text = title
        binding.textStatus.visibility = View.GONE
        binding.textDistance.text = ""
        binding.textDates.text = ""
        rowsAdapter.submit(listOf(DetailRow.Text(description.ifBlank { "No details available" })))
        osmUrl = poiId?.let { osmUrlFromPoiId(it) }
        binding.buttonOpenOsm.visibility = if (osmUrl != null) View.VISIBLE else View.GONE

        binding.buttonClose.setOnClickListener { dismiss() }
        binding.buttonBackToMap.setOnClickListener {
            dismiss()
            activity?.findViewById<BottomNavigationView>(R.id.bottomNavigation)
                ?.selectedItemId = R.id.mapFragment
        }
        binding.buttonOpenOsm.setOnClickListener { osmUrl?.let(::openUrl) }

        if (poiId != null) {
            val noteId = NOTE_POI_ID.find(poiId)?.groupValues?.get(1)?.toLongOrNull()
            viewLifecycleOwner.lifecycleScope.launch {
                val repository = PoiManager.getRepository(requireContext())
                val poi = runCatching { repository.getPoiById(poiId) }
                    .onFailure { Timber.e(it, "Loading POI $poiId for details") }
                    .getOrNull() ?: return@launch
                val pendingFlow = if (noteId != null) repository.observePendingActions(noteId) else flowOf(emptyList())
                val postingFlow = NoteActionQueue.get(requireContext()).posting
                // Note row, queue and in-flight set together: any of them changing re-renders.
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(repository.observeNoteByPoiId(poiId), pendingFlow, postingFlow) { note, pending, posting ->
                        Triple(note, pending, noteId != null && noteId in posting)
                    }.collect { (note, pending, inFlight) ->
                        if (_binding != null) render(poi, note, pending, inFlight, distanceArg)
                    }
                }
            }
        }
    }

    private fun render(poi: Poi, note: OsmNote?, pending: List<NoteActionRequest>, inFlight: Boolean, distanceArg: Float) {
        binding.textTitle.text = poi.name
        showDistance(poi, distanceArg)

        when {
            note != null -> renderNote(note, pending, inFlight)
            poi.source == "osm_fixme" -> renderFixme(poi)
            else -> renderPlain(poi)
        }

        osmUrl = note?.url ?: osmUrlFromPoiId(poi.id)
        binding.buttonOpenOsm.visibility = if (osmUrl != null) View.VISIBLE else View.GONE
    }

    private fun renderNote(note: OsmNote, pending: List<NoteActionRequest>, inFlight: Boolean) {
        setStatus(
            when (note.status) {
                NoteStatus.OPEN -> "OPEN"
                NoteStatus.CLOSED -> "CLOSED"
                NoteStatus.HIDDEN -> "HIDDEN"
            },
            highlighted = note.isOpen
        )
        binding.textDates.text = buildString {
            append("Created ${dateFormat.format(note.createdAt)}")
            note.closedAt?.let { append(" · closed ${dateFormat.format(it)}") }
            val replies = note.comments.size - 1
            if (replies > 0) append(" · $replies ${if (replies == 1) "reply" else "replies"}")
            if (pending.isNotEmpty()) {
                append(" · ${pending.size} ${if (pending.size == 1) "action" else "actions"} pending")
            }
        }

        // A bare close or reopen is worth a row; an empty plain comment is not. Queued actions
        // follow the thread as what they will become, marked pending until osm.org confirms.
        val me = OsmAuthManager.get(requireContext()).state.value as? OsmAuthState.LoggedIn
        val rows: List<DetailRow> = note.comments
            .filter { it.text.isNotBlank() || it.action != NoteAction.COMMENTED }
            .map { DetailRow.Comment(it) } +
            pending.map { DetailRow.Comment(it.asComment(me), pending = true) }
        rowsAdapter.submit(rows.ifEmpty { listOf(DetailRow.Text("No text.")) })
        if (pending.isNotEmpty()) binding.recyclerRows.scrollToPosition(rows.size - 1)

        showActions(note, inFlight)
    }

    /** The thread entry a queued action will turn into, attributed to the logged-in user. */
    private fun NoteActionRequest.asComment(me: OsmAuthState.LoggedIn?) = NoteComment(
        date = Date(createdAt),
        author = me?.displayName ?: "You",
        uid = me?.uid,
        action = kind.threadAction,
        text = text
    )

    private fun renderFixme(poi: Poi) {
        setStatus("FIXME", highlighted = true)
        binding.textDates.text = "Fetched ${dateFormat.format(poi.createdAt)}"
        val fixmeText = poi.tags["fixme"]?.takeIf { it.isNotBlank() }
            ?: poi.description?.removePrefix("Needs fixing:")?.trim().orEmpty()
        val rows = mutableListOf<DetailRow>()
        rows += DetailRow.Text(fixmeText.ifBlank { "No fixme text." })
        poi.tags.entries
            .sortedWith(compareBy({ it.key != "fixme" }, { it.key }))
            .forEach { (key, value) -> rows += DetailRow.Tag(key, value) }
        rowsAdapter.submit(rows)
        binding.actionRow.visibility = View.GONE
    }

    /** A note POI without an `osm_notes` row (pre-v4 data): the flattened text it carries. */
    private fun renderPlain(poi: Poi) {
        setStatus("NOTE", highlighted = true)
        binding.textDates.text = "Created ${dateFormat.format(poi.createdAt)}"
        val text = poi.notes?.takeIf { it.isNotBlank() } ?: poi.description.orEmpty()
        rowsAdapter.submit(listOf(DetailRow.Text(text.ifBlank { "No details available" })))
        binding.actionRow.visibility = View.GONE
    }

    private fun setStatus(label: String, highlighted: Boolean) {
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.text = label
        // Open notes and fixmes get the primary pill; closed and hidden ones a muted one.
        val bg = MaterialColors.getColor(
            binding.textStatus,
            if (highlighted) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorSurfaceVariant
        )
        val fg = MaterialColors.getColor(
            binding.textStatus,
            if (highlighted) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        binding.textStatus.setBackgroundColor(bg)
        binding.textStatus.setTextColor(fg)
    }

    /**
     * Comment / close / reopen for a logged-in user (item 5), with the buttons that fit the
     * note's (possibly optimistic) status: open gets Comment and Close, closed gets Reopen.
     * All three are disabled while a request for this note is on the wire; queued-but-offline
     * does not block, more can be queued behind it.
     */
    private fun showActions(note: OsmNote, inFlight: Boolean) {
        val loggedIn = OsmAuthManager.get(requireContext()).isLoggedIn
        binding.buttonComment.visibility = if (note.isOpen) View.VISIBLE else View.GONE
        binding.buttonCloseNote.visibility = if (note.isOpen) View.VISIBLE else View.GONE
        binding.buttonReopen.visibility = if (note.status == NoteStatus.CLOSED) View.VISIBLE else View.GONE
        val enabled = loggedIn && !inFlight
        binding.buttonComment.isEnabled = enabled
        binding.buttonCloseNote.isEnabled = enabled
        binding.buttonReopen.isEnabled = enabled
        binding.actionRow.visibility = if (loggedIn && note.status != NoteStatus.HIDDEN) View.VISIBLE else View.GONE
        binding.buttonComment.setOnClickListener {
            promptAction(note.noteId, NoteActionKind.COMMENT, "Comment on note ${note.noteId}", "Your comment", "Send", requireText = true)
        }
        binding.buttonCloseNote.setOnClickListener {
            promptAction(note.noteId, NoteActionKind.CLOSE, "Close note ${note.noteId}", "Closing comment (optional)", "Close note", requireText = false)
        }
        binding.buttonReopen.setOnClickListener {
            promptAction(note.noteId, NoteActionKind.REOPEN, "Reopen note ${note.noteId}", "Comment (optional)", "Reopen", requireText = false)
        }
    }

    /**
     * A small dialog with one text box. The positive button queues the action through
     * [NoteActionQueue]; it stays disabled while the text is blank when [requireText] (the API
     * refuses an empty comment; close and reopen take none).
     */
    private fun promptAction(
        noteId: Long,
        kind: NoteActionKind,
        title: String,
        hint: String,
        positive: String,
        requireText: Boolean
    ) {
        val input = DialogNoteActionBinding.inflate(layoutInflater)
        input.inputLayout.hint = hint
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { _, _ ->
                val text = input.inputText.text?.toString().orEmpty().trim()
                NoteActionQueue.get(requireContext()).enqueue(noteId, kind, text)
                Toast.makeText(context, "Queued: $positive", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.show()
        if (requireText) {
            val send = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            send.isEnabled = false
            input.inputText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    send.isEnabled = !s.isNullOrBlank()
                }
            })
        }
        input.inputText.requestFocus()
    }

    /** The distance the list computed, or one from the last known fix when opened from a ping or marker. */
    private fun showDistance(poi: Poi, distanceArg: Float) {
        val meters = if (!distanceArg.isNaN()) distanceArg else lastKnownLocation()?.let {
            poi.distanceTo(it.latitude, it.longitude)
        }
        binding.textDistance.text = meters?.let { "${formatDistance(it)} away" } ?: ""
    }

    private fun lastKnownLocation(): Location? {
        val context = context ?: return null
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Timber.w(it, "No activity for $url")
            Toast.makeText(context, "No browser to open $url", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerRows.adapter = null
        _binding = null
    }

    companion object {
        const val TAG = "NoteDetailsDialogFragment"
        private const val ARG_POI_ID = "note_id"
        private const val ARG_TITLE = "title"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_DISTANCE = "distance"

        private val NOTE_POI_ID = Regex("^${OsmNote.POI_ID_PREFIX}(\\d+)$")
        private val ELEMENT_POI_ID = Regex("^overpass_(node|way|relation)_(\\d+)$")

        /**
         * The osm.org page derivable from a POI id: `osm_note_{id}` or `overpass_{type}_{id}`.
         * Item 10 gives fixmes structured element fields; until then the id string is the source.
         */
        fun osmUrlFromPoiId(poiId: String): String? {
            NOTE_POI_ID.find(poiId)?.let { return "https://www.openstreetmap.org/note/${it.groupValues[1]}" }
            ELEMENT_POI_ID.find(poiId)?.let {
                return "https://www.openstreetmap.org/${it.groupValues[1]}/${it.groupValues[2]}"
            }
            return null
        }

        fun newInstance(
            noteId: String,
            title: String,
            description: String,
            distanceMeters: Float? = null
        ): NoteDetailsDialogFragment {
            return NoteDetailsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_POI_ID, noteId)
                    putString(ARG_TITLE, title)
                    putString(ARG_DESCRIPTION, description)
                    putFloat(ARG_DISTANCE, distanceMeters ?: Float.NaN)
                }
            }
        }
    }
}
