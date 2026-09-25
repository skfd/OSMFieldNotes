package com.surveyme.presentation.muted

import android.location.Location
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.surveyme.core.FixmeSummary
import com.surveyme.data.database.PoiWithNote
import com.surveyme.data.model.Poi
import com.surveyme.databinding.ItemMutedBinding
import com.surveyme.presentation.notes.formatDistance

/**
 * Rows of the muted list (item 13): kind and distance on top, the item's first line under it,
 * an Unmute button. A tap anywhere else opens the details sheet through the fragment.
 */
class MutedItemsAdapter(
    private val onOpen: (Poi) -> Unit,
    private val onUnmute: (Poi) -> Unit
) : ListAdapter<PoiWithNote, MutedItemsAdapter.Holder>(Diff()) {

    /** Where the walker is, for the distance to the item; null hides it. */
    var here: Location? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMutedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemMutedBinding) : RecyclerView.ViewHolder(binding.root) {

        private fun item(): PoiWithNote? =
            if (adapterPosition != RecyclerView.NO_POSITION) getItem(adapterPosition) else null

        init {
            binding.root.setOnClickListener { item()?.let { onOpen(it.poi) } }
            binding.buttonUnmute.setOnClickListener { item()?.let { onUnmute(it.poi) } }
        }

        fun bind(row: PoiWithNote) = with(binding) {
            val poi = row.poi
            val distance = here?.let { formatDistance(poi.distanceTo(it.latitude, it.longitude)) + " away" }
            textMeta.text = listOfNotNull(kindLabel(row), distance).joinToString(" · ")
            textTitle.text = firstLine(row)
            val secondary = if (poi.isFixme) FixmeSummary.secondaryLine(poi.tags, poi.osmElementType) else ""
            textSecondary.text = secondary
            textSecondary.visibility = if (secondary.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private class Diff : DiffUtil.ItemCallback<PoiWithNote>() {
        override fun areItemsTheSame(a: PoiWithNote, b: PoiWithNote) = a.poi.id == b.poi.id
        override fun areContentsTheSame(a: PoiWithNote, b: PoiWithNote) = a == b
    }

    companion object {
        /** "Note 12345", "Fixme", "Waypoint". */
        fun kindLabel(row: PoiWithNote): String = when {
            row.note != null -> "Note ${row.note.noteId}"
            row.poi.isNote -> "Note"
            row.poi.isFixme -> "Fixme"
            else -> "Waypoint"
        }

        /** The fixme text, or the note's first non-empty line, or the row's name. */
        fun firstLine(row: PoiWithNote): String {
            val poi = row.poi
            if (poi.isFixme) return FixmeSummary.primaryLine(poi.tags)
            val summary = (poi.description ?: poi.name).trim()
            return summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: poi.name
        }
    }
}
