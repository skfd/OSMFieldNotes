package com.surveyme.presentation.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.surveyme.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

class NotesAdapter(
    private val onNoteClick: (NoteItem) -> Unit
) : ListAdapter<NoteItem, NotesAdapter.NoteViewHolder>(NoteDiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
                textNoteId.text = item.poi.name
                // Structured thread when we have it; the flattened POI text for fixmes and pre-v4 rows.
                textDescription.text = note?.openingText?.takeIf { it.isNotBlank() } ?: item.poi.description
                textDate.text = buildString {
                    append("Created: ${dateFormat.format(note?.createdAt ?: item.poi.createdAt)}")
                    if (note != null) {
                        if (!note.isOpen) {
                            append(" · ${note.status.apiValue}")
                            note.closedAt?.let { append(" ${dateFormat.format(it)}") }
                        }
                        val replies = note.comments.size - 1
                        if (replies > 0) append(" · $replies ${if (replies == 1) "reply" else "replies"}")
                    }
                }

                if (item.poi.source == "osm_fixme") {
                    textType.text = "FIXME"
                    textType.visibility = android.view.View.VISIBLE
                } else if (item.poi.source == "osm_notes") {
                    textType.text = if (note != null && !note.isOpen) "NOTE · ${note.status.apiValue.uppercase()}" else "NOTE"
                    textType.visibility = android.view.View.VISIBLE
                } else {
                    textType.visibility = android.view.View.GONE
                }
                
                if (item.distance != null) {
                    val distanceMeters = item.distance.roundToInt()
                    if (distanceMeters < 1000) {
                        textDistance.text = "$distanceMeters m"
                    } else {
                        val distanceKm = String.format("%.1f", distanceMeters / 1000f)
                        textDistance.text = "$distanceKm km"
                    }
                } else {
                    textDistance.text = ""
                }
            }
        }
    }
}

class NoteDiffCallback : DiffUtil.ItemCallback<NoteItem>() {
    override fun areItemsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
        return oldItem.poi.id == newItem.poi.id
    }

    override fun areContentsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
        return oldItem == newItem
    }
}
