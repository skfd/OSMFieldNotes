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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.surveyme.R
import com.surveyme.data.PoiManager
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.databinding.FragmentNoteDetailsBinding
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Full-height bottom sheet for one note or fixme. Opened from the Notes list, from a map marker
 * and from the proximity ping (all through [NotesFragment.pendingNote] / [newInstance]).
 *
 * Notes render their thread as comment rows from the `osm_notes` row; fixmes render the fixme
 * text and their raw tags. The title and description arguments are the fallback for a POI that
 * has no note row (pre-v4 data) or that was expired between the ping and the tap.
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
            viewLifecycleOwner.lifecycleScope.launch {
                val repository = PoiManager.getRepository(requireContext())
                val poi = runCatching { repository.getPoiById(poiId) }
                    .onFailure { Timber.e(it, "Loading POI $poiId for details") }
                    .getOrNull()
                val note = runCatching { repository.getNoteByPoiId(poiId) }
                    .onFailure { Timber.e(it, "Loading note for POI $poiId") }
                    .getOrNull()
                if (_binding == null) return@launch
                if (poi != null) render(poi, note, distanceArg)
            }
        }
    }

    private fun render(poi: Poi, note: OsmNote?, distanceArg: Float) {
        binding.textTitle.text = poi.name
        showDistance(poi, distanceArg)

        when {
            note != null -> renderNote(note)
            poi.source == "osm_fixme" -> renderFixme(poi)
            else -> renderPlain(poi)
        }

        osmUrl = note?.url ?: osmUrlFromPoiId(poi.id)
        binding.buttonOpenOsm.visibility = if (osmUrl != null) View.VISIBLE else View.GONE
    }

    private fun renderNote(note: OsmNote) {
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
        }

        // A bare close or reopen is worth a row; an empty plain comment is not.
        val rows: List<DetailRow> = note.comments
            .filter { it.text.isNotBlank() || it.action != NoteAction.COMMENTED }
            .map { DetailRow.Comment(it) }
        rowsAdapter.submit(rows.ifEmpty { listOf(DetailRow.Text("No text.")) })

        showActions(note)
    }

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
     * Item 6 will post comment / close / reopen. The row is laid out and the buttons that fit the
     * note's status are chosen here, but the row stays hidden until OSM login (item 5) exists.
     */
    private fun showActions(note: OsmNote) {
        val loggedIn = false // item 5
        binding.buttonCloseNote.visibility = if (note.isOpen) View.VISIBLE else View.GONE
        binding.buttonReopen.visibility = if (note.status == NoteStatus.CLOSED) View.VISIBLE else View.GONE
        binding.buttonComment.isEnabled = loggedIn
        binding.buttonCloseNote.isEnabled = loggedIn
        binding.buttonReopen.isEnabled = loggedIn
        binding.actionRow.visibility = if (loggedIn && note.status != NoteStatus.HIDDEN) View.VISIBLE else View.GONE
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
