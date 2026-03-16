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
                textNoteId.text = item.poi.name
                textDescription.text = item.poi.description
                textDate.text = "Created: ${dateFormat.format(item.poi.createdAt)}"
                
                if (item.poi.source == "osm_fixme") {
                    textType.text = "FIXME"
                    textType.visibility = android.view.View.VISIBLE
                } else if (item.poi.source == "osm_notes") {
                    textType.text = "NOTE"
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
