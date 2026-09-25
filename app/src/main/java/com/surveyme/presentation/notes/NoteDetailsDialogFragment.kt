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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.surveyme.R
import com.surveyme.core.EvidenceSnapshot
import com.surveyme.core.LastKnownLocation
import com.surveyme.core.OsmElementRef
import com.surveyme.data.EvidenceStore
import com.surveyme.data.NoteActionQueue
import com.surveyme.data.OsmAuthManager
import com.surveyme.data.OsmAuthState
import com.surveyme.data.PoiManager
import com.surveyme.data.model.Evidence
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteActionKind
import com.surveyme.data.model.NoteActionRequest
import com.surveyme.data.model.NoteComment
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.databinding.DialogNoteActionBinding
import com.surveyme.databinding.FragmentNoteDetailsBinding
import com.surveyme.presentation.evidence.EvidenceIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
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

    /** Local copy of the fixme's dismissed flag; the sheet observes the note row, not the POI row. */
    private var dismissed = false

    /**
     * The POI row as loaded, updated locally when this sheet mutes or marks it visited: render()
     * re-runs on every note / queue emission and must not paint the stale flags back.
     */
    private var currentPoi: Poi? = null

    /**
     * What a capture is pinned to (item 11): the snapshot of the item as rendered, and once a
     * capture starts, the walker's fix and the file the camera writes into. Kept in the saved
     * state because the camera app routinely kills the caller; on restore the TakePicture
     * callback still lands here and needs to know what the photo was for.
     */
    data class EvidenceTarget(
        val poiId: String,
        val noteId: Long?,
        val title: String,
        val summary: String,
        val source: String,
        val itemLat: Double,
        val itemLon: Double,
        val lat: Double? = null,
        val lon: Double? = null,
        val photoPath: String? = null
    ) {
        fun toBundle(): Bundle = Bundle().apply {
            putString("poiId", poiId)
            noteId?.let { putLong("noteId", it) }
            putString("title", title)
            putString("summary", summary)
            putString("source", source)
            putDouble("itemLat", itemLat)
            putDouble("itemLon", itemLon)
            lat?.let { putDouble("lat", it) }
            lon?.let { putDouble("lon", it) }
            putString("photoPath", photoPath)
        }

        companion object {
            fun fromBundle(b: Bundle): EvidenceTarget? {
                val poiId = b.getString("poiId") ?: return null
                return EvidenceTarget(
                    poiId = poiId,
                    noteId = if (b.containsKey("noteId")) b.getLong("noteId") else null,
                    title = b.getString("title").orEmpty(),
                    summary = b.getString("summary").orEmpty(),
                    source = b.getString("source").orEmpty(),
                    itemLat = b.getDouble("itemLat"),
                    itemLon = b.getDouble("itemLon"),
                    lat = if (b.containsKey("lat")) b.getDouble("lat") else null,
                    lon = if (b.containsKey("lon")) b.getDouble("lon") else null,
                    photoPath = b.getString("photoPath")
                )
            }
        }
    }

    /** The item as last rendered; null until the POI row is loaded. */
    private var evidenceTarget: EvidenceTarget? = null

    /** The capture in progress (camera or picker open), with its fix and file. */
    private var pendingCapture: EvidenceTarget? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val capture = pendingCapture ?: return@registerForActivityResult
        pendingCapture = null
        val path = capture.photoPath ?: return@registerForActivityResult
        if (!saved || File(path).length() == 0L) {
            EvidenceStore.deleteFile(path)
            if (saved) Toast.makeText(context, R.string.evidence_photo_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val appContext = context?.applicationContext ?: return@registerForActivityResult
        saveEvidence(appContext, capture, Evidence.KIND_PHOTO, text = null, photoPath = path)
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val capture = pendingCapture ?: return@registerForActivityResult
        pendingCapture = null
        if (uri == null) return@registerForActivityResult
        val appContext = context?.applicationContext ?: return@registerForActivityResult
        evidenceScope.launch {
            val file = EvidenceStore.importPhoto(appContext) { appContext.contentResolver.openInputStream(uri) }
            if (file == null) {
                withContext(Dispatchers.Main) { Toast.makeText(appContext, R.string.evidence_photo_failed, Toast.LENGTH_SHORT).show() }
            } else {
                saveEvidence(appContext, capture, Evidence.KIND_PHOTO, text = null, photoPath = file.absolutePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            pendingCapture = it.getBundle(STATE_PENDING_CAPTURE)?.let(EvidenceTarget::fromBundle)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCapture?.let { outState.putBundle(STATE_PENDING_CAPTURE, it.toBundle()) }
    }

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
        rowsAdapter.thumbnailScope = viewLifecycleOwner.lifecycleScope
        rowsAdapter.onEvidenceClick = { EvidenceIntents.view(requireContext(), it) }
        binding.chipAddPhoto.setOnClickListener { startCamera() }
        binding.chipPickPhoto.setOnClickListener { startPicker() }
        binding.chipAddText.setOnClickListener { promptTextEvidence() }

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
                currentPoi = poi
                val pendingFlow = if (noteId != null) repository.observePendingActions(noteId) else flowOf(emptyList())
                val postingFlow = NoteActionQueue.get(requireContext()).posting
                val evidenceFlow = repository.observeEvidenceForPoi(poiId)
                // Note row, queue, in-flight set and pinned evidence together: any of them
                // changing re-renders.
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(repository.observeNoteByPoiId(poiId), pendingFlow, postingFlow, evidenceFlow) { note, pending, posting, evidence ->
                        RenderInput(note, pending, noteId != null && noteId in posting, evidence)
                    }.collect { input ->
                        if (_binding != null) render(currentPoi ?: poi, input.note, input.pending, input.inFlight, input.evidence, distanceArg)
                    }
                }
            }
        }
    }

    private class RenderInput(
        val note: OsmNote?,
        val pending: List<NoteActionRequest>,
        val inFlight: Boolean,
        val evidence: List<Evidence>
    )

    private fun render(
        poi: Poi,
        note: OsmNote?,
        pending: List<NoteActionRequest>,
        inFlight: Boolean,
        evidence: List<Evidence>,
        distanceArg: Float
    ) {
        binding.textTitle.text = poi.name
        showDistance(poi, distanceArg)

        val rows = when {
            note != null -> renderNote(note, pending, inFlight)
            poi.source == "osm_fixme" -> renderFixme(poi)
            else -> renderPlain(poi)
        }
        // Item 11: what is pinned to this item, after the thread, plus the capture chips.
        val evidenceRows = if (evidence.isEmpty()) emptyList() else
            listOf(DetailRow.Header(getString(R.string.evidence_section, evidence.size))) + evidence.map { DetailRow.EvidenceItem(it) }
        rowsAdapter.submit(rows + evidenceRows)
        if (pending.isNotEmpty()) binding.recyclerRows.scrollToPosition(rows.size - 1)
        evidenceTarget = EvidenceTarget(
            poiId = poi.id,
            noteId = note?.noteId,
            title = poi.name,
            summary = EvidenceSnapshot.summaryOf(
                note?.openingText ?: poi.tags["fixme"] ?: poi.notes ?: poi.description
            ),
            source = poi.source,
            itemLat = poi.latitude,
            itemLon = poi.longitude
        )
        binding.textEvidenceTitle.text = getString(R.string.evidence_section, evidence.size)
        binding.evidenceRow.visibility = View.VISIBLE

        // Item 10: a fixme hands its element off; a note hands off the first element its thread
        // links (no dismiss for a note: the hand-off is about the element, not the note).
        val linked = when {
            poi.source == "osm_fixme" -> poi.element
            note != null && !note.isPending -> OsmElementRef.firstInText(note.comments.joinToString("\n") { it.text })
            else -> null
        }
        if (linked != null) showHandoff(poi, linked, isFixme = poi.source == "osm_fixme") else binding.handoffRow.visibility = View.GONE

        // A placeholder (queued create) has no page on osm.org yet.
        osmUrl = note?.takeIf { !it.isPending }?.url ?: poi.element?.browseUrl ?: osmUrlFromPoiId(poi.id)
        binding.buttonOpenOsm.visibility = if (osmUrl != null) View.VISIBLE else View.GONE
    }

    /** Header, labels and action row for a note; returns the thread rows for the caller to submit. */
    private fun renderNote(note: OsmNote, pending: List<NoteActionRequest>, inFlight: Boolean): List<DetailRow> {
        setStatus(
            when {
                note.isPending -> "PENDING"
                note.status == NoteStatus.OPEN -> "OPEN"
                note.status == NoteStatus.CLOSED -> "CLOSED"
                else -> "HIDDEN"
            },
            highlighted = note.isOpen && !note.isPending
        )
        binding.textDates.text = buildString {
            append("Created ${dateFormat.format(note.createdAt)}")
            if (note.isPending) append(" · waiting to be posted to osm.org")
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

        // Every label (item 8); the list row shows only the first few.
        LabelChips.bind(binding.chipLabels, note.labels)

        // Nothing can be posted to a note osm.org has not created yet.
        if (note.isPending) binding.actionRow.visibility = View.GONE else showActions(note, inFlight)
        return rows.ifEmpty { listOf(DetailRow.Text("No text.")) }
    }

    /** The thread entry a queued action will turn into, attributed to the logged-in user. */
    private fun NoteActionRequest.asComment(me: OsmAuthState.LoggedIn?) = NoteComment(
        date = Date(createdAt),
        author = me?.displayName ?: "You",
        uid = me?.uid,
        action = kind.threadAction,
        text = text
    )

    private fun renderFixme(poi: Poi): List<DetailRow> {
        dismissed = poi.isMuted
        setStatus(if (dismissed) "FIXME · DISMISSED" else "FIXME", highlighted = !dismissed)
        binding.textDates.text = buildString {
            append("Fetched ${dateFormat.format(poi.createdAt)}")
            poi.osmElementVersion?.let { append(" · v$it") }
            if (poi.isVisited) append(" · visited")
        }
        val fixmeText = poi.tags["fixme"]?.takeIf { it.isNotBlank() }
            ?: poi.description?.removePrefix("Needs fixing:")?.trim().orEmpty()
        val rows = mutableListOf<DetailRow>()
        rows += DetailRow.Text(fixmeText.ifBlank { "No fixme text." })
        poi.tags.entries
            .sortedWith(compareBy({ it.key != "fixme" }, { it.key }))
            .forEach { (key, value) -> rows += DetailRow.Tag(key, value) }
        binding.chipLabels.visibility = View.GONE
        binding.actionRow.visibility = View.GONE
        return rows
    }

    /** A note POI without an `osm_notes` row (pre-v4 data): the flattened text it carries. */
    private fun renderPlain(poi: Poi): List<DetailRow> {
        setStatus("NOTE", highlighted = true)
        binding.textDates.text = "Created ${dateFormat.format(poi.createdAt)}"
        val text = poi.notes?.takeIf { it.isNotBlank() } ?: poi.description.orEmpty()
        binding.chipLabels.visibility = View.GONE
        binding.actionRow.visibility = View.GONE
        return listOf(DetailRow.Text(text.ifBlank { "No details available" }))
    }

    // Evidence capture (item 11). Every capture snaps the walker's fix at the moment the
    // camera, picker or text dialog opens; the row is written by [saveEvidence] on a
    // process-wide scope so a sheet dismissed mid-way does not lose it.

    private fun startCamera() {
        val context = context ?: return
        val target = evidenceTarget ?: return
        val file = EvidenceStore.newPhotoFile(context)
        pendingCapture = target.withFix(context).copy(photoPath = file.absolutePath)
        runCatching { takePicture.launch(EvidenceStore.uriFor(context, file)) }
            .onFailure {
                Timber.w(it, "No camera app")
                pendingCapture = null
                EvidenceStore.deleteFile(file.absolutePath)
                Toast.makeText(context, R.string.evidence_no_camera, Toast.LENGTH_SHORT).show()
            }
    }

    private fun startPicker() {
        val context = context ?: return
        val target = evidenceTarget ?: return
        pendingCapture = target.withFix(context)
        runCatching { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure {
                Timber.w(it, "No picker")
                pendingCapture = null
                Toast.makeText(context, R.string.evidence_no_viewer, Toast.LENGTH_SHORT).show()
            }
    }

    private fun promptTextEvidence() {
        val context = context ?: return
        val target = evidenceTarget?.withFix(context) ?: return
        val input = DialogNoteActionBinding.inflate(layoutInflater)
        input.inputLayout.hint = getString(R.string.evidence_text_hint)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.evidence_text_title)
            .setView(input.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.evidence_text_save) { _, _ ->
                val text = input.inputText.text?.toString().orEmpty().trim()
                if (text.isNotEmpty()) saveEvidence(context.applicationContext, target, Evidence.KIND_TEXT, text = text, photoPath = null)
            }
            .create()
        dialog.show()
        val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        save.isEnabled = false
        input.inputText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                save.isEnabled = !s.isNullOrBlank()
            }
        })
        input.inputText.requestFocus()
    }

    private fun EvidenceTarget.withFix(context: Context): EvidenceTarget {
        val here = LastKnownLocation.get(context)
        return copy(lat = here?.latitude, lon = here?.longitude)
    }

    private fun saveEvidence(appContext: Context, target: EvidenceTarget, kind: String, text: String?, photoPath: String?) {
        val row = Evidence(
            poiId = target.poiId,
            noteId = target.noteId,
            kind = kind,
            text = text,
            photoPath = photoPath,
            lat = target.lat,
            lon = target.lon,
            itemTitle = target.title,
            itemSummary = target.summary,
            itemSource = target.source,
            itemLat = target.itemLat,
            itemLon = target.itemLon
        )
        evidenceScope.launch {
            val result = runCatching { PoiManager.getRepository(appContext).addEvidence(row) }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Timber.d("Pinned $kind evidence $it to ${target.poiId}")
                    Toast.makeText(
                        appContext,
                        if (kind == Evidence.KIND_PHOTO) R.string.evidence_saved_photo else R.string.evidence_saved_text,
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Timber.e(it, "Saving evidence for ${target.poiId}")
                    EvidenceStore.deleteFile(photoPath)
                    Toast.makeText(appContext, R.string.evidence_photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
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

    /**
     * The "Fix in editor" chips (item 10): osm.org, the web editor, Vespucci and StreetComplete
     * when installed, and a `geo:` chooser for any map app. Every editor tap marks a fixme
     * visited (a record that it was looked at; pings are stopped by "Dismiss for now", which
     * sets `isMuted`, the flag the tracking service filters on).
     */
    private fun showHandoff(poi: Poi, element: OsmElementRef, isFixme: Boolean) {
        val context = context ?: return
        val options = ElementHandoff.options(context, element, poi.latitude, poi.longitude)
        binding.textHandoffTitle.text = "Fix in editor · ${element.type} ${element.id}"
        binding.chipHandoff.removeAllViews()
        options.forEach { option ->
            binding.chipHandoff.addView(actionChip(option.label) {
                if (openIntent(option.intent, option.label) && isFixme) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching { PoiManager.getRepository(context).markAsVisited(poi.id) }
                            .onFailure { Timber.e(it, "Marking ${poi.id} visited") }
                            .onSuccess { currentPoi = currentPoi?.copy(isVisited = true) }
                    }
                    if (!dismissed) {
                        Toast.makeText(context, "Marked visited. Dismiss for now to stop pings.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (isFixme) {
            binding.chipHandoff.addView(actionChip(if (dismissed) "Undo dismiss" else "Dismiss for now") {
                val muted = !dismissed
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { PoiManager.getRepository(context).setMuted(poi.id, muted) }
                        .onFailure { Timber.e(it, "Muting ${poi.id}") }
                        .onSuccess {
                            currentPoi = currentPoi?.copy(isMuted = muted)
                            if (_binding == null) return@onSuccess
                            dismissed = muted
                            setStatus(if (muted) "FIXME · DISMISSED" else "FIXME", highlighted = !muted)
                            showHandoff(poi, element, isFixme = true)
                            Toast.makeText(context, if (muted) "Dismissed: no more pings for this fixme" else "Pings again", Toast.LENGTH_SHORT).show()
                        }
                }
            })
        }
        binding.handoffRow.visibility = View.VISIBLE
    }

    private fun actionChip(label: String, onClick: () -> Unit): Chip = Chip(requireContext()).apply {
        text = label
        isCheckable = false
        setEnsureMinTouchTargetSize(false)
        setOnClickListener { onClick() }
    }

    /** Starts [intent]; a missing handler is a toast, not a crash. Returns whether it started. */
    private fun openIntent(intent: Intent, label: String): Boolean =
        runCatching { startActivity(intent) }
            .onFailure {
                Timber.w(it, "No activity for $label: ${intent.data}")
                Toast.makeText(context, "Nothing installed to open $label", Toast.LENGTH_SHORT).show()
            }.isSuccess

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
        private const val STATE_PENDING_CAPTURE = "pending_capture"

        /** Evidence rows are written here, not on the sheet's lifecycle: a dismiss must not cancel the insert. */
        private val evidenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val NOTE_POI_ID = Regex("^${OsmNote.POI_ID_PREFIX}(\\d+)$")

        /**
         * The osm.org page derivable from a POI id: `osm_note_{id}` or `overpass_{type}_{id}`.
         * Used before the row is loaded; once it is, [Poi.element] (the v7 columns) wins.
         */
        fun osmUrlFromPoiId(poiId: String): String? {
            NOTE_POI_ID.find(poiId)?.let { return "https://www.openstreetmap.org/note/${it.groupValues[1]}" }
            return OsmElementRef.fromPoiId(poiId)?.browseUrl
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
