package com.surveyme.presentation.notes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.surveyme.core.NoteLabels

/** One colour per audience, shared by the map markers and the label chips. */
object AudienceColors {
    private const val SURVEYOR = 0xFF2E7D32.toInt()   // green: go there
    private const val TELEMAPPER = 0xFF1565C0.toInt() // blue: desk edit
    private const val JOSM = 0xFF6A1B9A.toInt()       // purple: expert
    private const val NONE = 0xFF757575.toInt()       // grey: no audience rule matched

    fun colorFor(audience: String?): Int = when (audience) {
        NoteLabels.SURVEYOR -> SURVEYOR
        NoteLabels.TELEMAPPER -> TELEMAPPER
        NoteLabels.JOSM -> JOSM
        else -> NONE
    }

    /** The colour a note with these labels is drawn in. */
    fun colorForLabels(labels: Set<String>): Int = colorFor(NoteLabels.audienceOf(labels))
}

/**
 * Renders a note's labels as small read-only chips into a [ChipGroup], reusing the group's
 * children so a RecyclerView row does not inflate on every bind. Audience chips are filled in
 * their colour; the rest are outlined.
 */
object LabelChips {
    fun bind(group: ChipGroup, labels: Set<String>, max: Int = Int.MAX_VALUE) {
        val ordered = NoteLabels.ordered(labels).take(max)
        if (ordered.isEmpty()) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        while (group.childCount > ordered.size) group.removeViewAt(group.childCount - 1)
        while (group.childCount < ordered.size) group.addView(newChip(group.context))
        ordered.forEachIndexed { index, label ->
            val chip = group.getChildAt(index) as Chip
            chip.text = NoteLabels.displayName(label)
            if (label in NoteLabels.AUDIENCES) {
                chip.chipBackgroundColor = ColorStateList.valueOf(AudienceColors.colorFor(label))
                chip.setTextColor(Color.WHITE)
                chip.chipStrokeWidth = 0f
            } else {
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorSurfaceVariant)
                )
                chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant))
                chip.chipStrokeWidth = 0f
            }
        }
    }

    private fun newChip(context: Context): Chip = Chip(context).apply {
        val density = context.resources.displayMetrics.density
        isClickable = false
        isCheckable = false
        isFocusable = false
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = 22 * density
        chipStartPadding = 6 * density
        chipEndPadding = 6 * density
        textStartPadding = 0f
        textEndPadding = 0f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
}
