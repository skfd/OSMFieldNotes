package com.surveyme.presentation.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.surveyme.core.FixmeSummary
import com.surveyme.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

class NotesAdapter(
    private val onNoteClick: (NoteItem) -> Unit
) : ListAdapter<NoteItem, NotesAdapter.NoteViewHolder>(NoteDiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private companion object {
        const val MAX_ROW_CHIPS = 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onNoteClick(getItem(adapterPosition))
                }
            }
        }

        fun bind(item: NoteItem) {
            binding.apply {
                val note = item.note
                val poi = item.poi
                if (poi.isFixme) {
                    // Item 12: the fixme text is the row; the element's main tags say what it is on.
                    textNoteId.text = FixmeSummary.primaryLine(poi.tags)
                    val secondary = FixmeSummary.secondaryLine(poi.tags, poi.element?.type)
                    textDescription.text = secondary
                    textDescription.visibility = if (secondary.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    textDate.text = buildString {
                        val edited = poi.lastEditedAt
                        if (edited != null) append("Edited: ${dateFormat.format(edited)}")
                        else append("Fetched: ${dateFormat.format(poi.createdAt)}")
                        poi.osmElementVersion?.let { append(" · v$it") }
                        if (poi.isVisited) append(" · visited")
                    }
                } else {
                    textNoteId.text = poi.name
                    // Structured thread when we have it; the flattened POI text for pre-v4 rows.
                    textDescription.text = note?.openingText?.takeIf { it.isNotBlank() } ?: poi.description
                    textDescription.visibility = android.view.View.VISIBLE
                    textDate.text = buildString {
                        append("Created: ${dateFormat.format(note?.createdAt ?: poi.createdAt)}")
                        if (note != null) {
                            if (!note.isOpen) {
                                append(" · ${note.status.apiValue}")
                                note.closedAt?.let { append(" ${dateFormat.format(it)}") }
                            }
                            val replies = note.comments.size - 1
                            if (replies > 0) append(" · $replies ${if (replies == 1) "reply" else "replies"}")
                        }
                    }
                }

                // A fixme dismissed for now (item 10) stays in the list, dimmed, and does not ping.
                val dismissed = poi.isDismissedFixme
                root.alpha = if (dismissed) 0.55f else 1f
                if (poi.isFixme) {
                    textType.text = if (dismissed) "FIXME · DISMISSED" else "FIXME"
                    textType.visibility = android.view.View.VISIBLE
                } else if (item.poi.source == "osm_notes") {
                    textType.text = when {
                        note != null && note.isPending -> "NOTE · PENDING"
                        note != null && !note.isOpen -> "NOTE · ${note.status.apiValue.uppercase()}"
                        item.pingable -> "NOTE · PINGS"
                        else -> "NOTE"
                    }
                    textType.visibility = android.view.View.VISIBLE
                } else {
                    textType.visibility = android.view.View.GONE
                }

                // A few labels per row (audience first); the details sheet shows them all.
                // A fixme carries its own labels on the POI (item 12).
                LabelChips.bind(chipLabels, note?.labels ?: poi.labels, max = MAX_ROW_CHIPS)

                textDistance.text = item.distance?.let { formatDistance(it) } ?: ""
            }
        }
    }
}

/** "320 m" under a kilometre, "1.2 km" above; shared by the list and the details sheet. */
fun formatDistance(meters: Float): String {
    val rounded = meters.roundToInt()
    return if (rounded < 1000) "$rounded m" else String.format(Locale.getDefault(), "%.1f km", rounded / 1000f)
}

class NoteDiffCallback : DiffUtil.ItemCallback<NoteItem>() {
    override fun areItemsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
        return oldItem.poi.id == newItem.poi.id
    }

    override fun areContentsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
        return oldItem == newItem
    }
}
