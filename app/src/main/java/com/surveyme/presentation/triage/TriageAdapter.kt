package com.surveyme.presentation.triage

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.surveyme.R
import com.surveyme.core.NoteLabels
import com.surveyme.core.NoteTriage
import com.surveyme.databinding.ItemTriageNoteBinding
import com.surveyme.presentation.notes.AudienceColors
import com.surveyme.presentation.notes.LabelChips
import java.text.SimpleDateFormat
import java.util.Locale

/** Rows of the triage list; every tap goes back to the fragment, which owns the queue and the dialogs. */
class TriageAdapter(
    private val onOpen: (TriageItem) -> Unit,
    private val onTag: (TriageItem, String) -> Unit,
    private val onSkip: (TriageItem) -> Unit,
    private val onChange: (TriageItem) -> Unit
) : ListAdapter<TriageItem, TriageAdapter.Holder>(Diff()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private companion object {
        const val MAX_ROW_CHIPS = 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTriageNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemTriageNoteBinding) : RecyclerView.ViewHolder(binding.root) {

        private fun item(): TriageItem? =
            if (adapterPosition != RecyclerView.NO_POSITION) getItem(adapterPosition) else null

        init {
            binding.root.setOnClickListener { item()?.let(onOpen) }
            binding.chipSurveyme.setOnClickListener { item()?.let { onTag(it, NoteTriage.TAG_SURVEYME) } }
            binding.chipSurveyafter.setOnClickListener { item()?.let { onTag(it, NoteTriage.TAG_SURVEYAFTER) } }
            binding.chipArmchair.setOnClickListener { item()?.let { onTag(it, NoteTriage.TAG_ARMCHAIR) } }
            binding.chipJosm.setOnClickListener { item()?.let { onTag(it, NoteTriage.TAG_JOSM) } }
            binding.chipSkip.setOnClickListener { item()?.let(onSkip) }
            binding.chipChange.setOnClickListener { item()?.let(onChange) }
        }

        fun bind(item: TriageItem) = with(binding) {
            val note = item.note
            val context = root.context
            val author = note.comments.firstOrNull()?.author ?: context.getString(R.string.triage_anonymous)
            textMeta.text = "Note ${note.noteId} · $author · ${dateFormat.format(note.createdAt)}"
            textText.text = note.openingText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            LabelChips.bind(chipLabels, note.labels, max = MAX_ROW_CHIPS)

            val pending = item.pendingText
            val tagged = item.strictTag
            when {
                pending != null -> {
                    textTagState.visibility = View.VISIBLE
                    textTagState.text = context.getString(R.string.triage_pending, pending.lineSequence().first())
                    showTagChips(false)
                    chipChange.visibility = View.GONE
                }
                tagged != null && !item.changing -> {
                    textTagState.visibility = View.VISIBLE
                    val shown = if (tagged == NoteTriage.TAG_SURVEYAFTER && note.surveyAfter != null) {
                        NoteTriage.commentFor(tagged, note.surveyAfter)
                    } else tagged
                    textTagState.text = context.getString(R.string.triage_tagged, shown)
                    showTagChips(false)
                    chipChange.visibility = View.VISIBLE
                }
                else -> {
                    textTagState.visibility = View.GONE
                    showTagChips(true)
                    chipChange.visibility = View.GONE
                }
            }
            val enabled = !item.inFlight
            listOf(chipSurveyme, chipSurveyafter, chipArmchair, chipJosm, chipChange).forEach { it.isEnabled = enabled }

            // The guess is filled in its audience colour; the rest are outlined. Reset on every
            // bind: rows are recycled.
            style(chipSurveyme, NoteTriage.TAG_SURVEYME, item.guess, NoteLabels.SURVEYOR)
            style(chipSurveyafter, NoteTriage.TAG_SURVEYAFTER, item.guess, NoteLabels.SURVEYOR)
            style(chipArmchair, NoteTriage.TAG_ARMCHAIR, item.guess, NoteLabels.TELEMAPPER)
            style(chipJosm, NoteTriage.TAG_JOSM, item.guess, NoteLabels.JOSM)
            outline(chipChange)
            outline(chipSkip)
        }

        private fun showTagChips(visible: Boolean) = with(binding) {
            val v = if (visible) View.VISIBLE else View.GONE
            chipSurveyme.visibility = v
            chipSurveyafter.visibility = v
            chipArmchair.visibility = v
            chipJosm.visibility = v
        }

        private fun style(chip: Chip, tag: String, guess: String?, audience: String) {
            val colour = AudienceColors.colorFor(audience)
            if (tag == guess) {
                chip.chipBackgroundColor = ColorStateList.valueOf(colour)
                chip.setTextColor(Color.WHITE)
                chip.chipStrokeWidth = 0f
            } else {
                chip.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
                chip.setTextColor(colour)
                chip.chipStrokeColor = ColorStateList.valueOf(colour)
                chip.chipStrokeWidth = chip.resources.displayMetrics.density
            }
        }

        private fun outline(chip: Chip) {
            val colour = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOutline)
            chip.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
            chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurface))
            chip.chipStrokeColor = ColorStateList.valueOf(colour)
            chip.chipStrokeWidth = chip.resources.displayMetrics.density
        }
    }

    class Diff : DiffUtil.ItemCallback<TriageItem>() {
        override fun areItemsTheSame(oldItem: TriageItem, newItem: TriageItem) = oldItem.note.noteId == newItem.note.noteId
        override fun areContentsTheSame(oldItem: TriageItem, newItem: TriageItem) = oldItem == newItem
    }
}
