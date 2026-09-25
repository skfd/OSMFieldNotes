package com.surveyme.presentation.evidence

import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.surveyme.R
import com.surveyme.data.EvidenceStore
import com.surveyme.data.model.Evidence
import com.surveyme.databinding.ItemEvidenceReviewBinding
import com.surveyme.presentation.notes.formatDistance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Rows of the review list (item 11). Every tap goes back to the fragment, which owns the
 * dialogs and the intents; thumbnails are decoded in [scope] (the view lifecycle's) and the
 * decode is cancelled on rebind so a recycled card never shows the wrong picture.
 */
class EvidenceReviewAdapter(
    private val scope: CoroutineScope,
    private val onOpen: (Evidence) -> Unit,
    private val onToggleReviewed: (Evidence) -> Unit,
    private val onShare: (Evidence) -> Unit,
    private val onDelete: (Evidence) -> Unit,
    private val onPhoto: (Evidence) -> Unit
) : ListAdapter<Evidence, EvidenceReviewAdapter.Holder>(Diff()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** Where the walker is, for the distance to the item; null hides it. */
    var here: Location? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemEvidenceReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) = holder.cancel()

    inner class Holder(private val binding: ItemEvidenceReviewBinding) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null
        private var boundPath: String? = null

        private fun item(): Evidence? =
            if (adapterPosition != RecyclerView.NO_POSITION) getItem(adapterPosition) else null

        init {
            binding.chipOpen.setOnClickListener { item()?.let(onOpen) }
            binding.chipReviewed.setOnClickListener { item()?.let(onToggleReviewed) }
            binding.chipShare.setOnClickListener { item()?.let(onShare) }
            binding.chipDelete.setOnClickListener { item()?.let(onDelete) }
            binding.imageThumb.setOnClickListener { item()?.let(onPhoto) }
        }

        fun bind(evidence: Evidence) = with(binding) {
            cancel()
            val context = root.context
            val distance = here?.let {
                val out = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, evidence.itemLat, evidence.itemLon, out)
                formatDistance(out[0]) + " away"
            }
            textMeta.text = listOfNotNull(
                EvidenceIntents.itemLabel(evidence),
                distance,
                dateFormat.format(evidence.createdAt),
                if (evidence.reviewed) context.getString(R.string.evidence_reviewed_label) else null
            ).joinToString(" · ")
            textItemTitle.text = if (evidence.itemSummary.isNotBlank() && evidence.itemSummary != evidence.itemTitle) {
                "${evidence.itemTitle} — ${evidence.itemSummary}"
            } else evidence.itemTitle

            val text = evidence.text?.takeIf { it.isNotBlank() }
            textBody.text = text ?: if (evidence.isPhoto) "Photo" else ""
            chipReviewed.text = context.getString(
                if (evidence.reviewed) R.string.evidence_action_unreview else R.string.evidence_action_reviewed
            )
            root.alpha = if (evidence.reviewed) 0.7f else 1f

            val path = evidence.photoPath
            boundPath = path
            imageThumb.setImageBitmap(null)
            if (path == null) {
                imageThumb.visibility = View.GONE
                return@with
            }
            imageThumb.visibility = View.VISIBLE
            val sizePx = imageThumb.layoutParams.width.takeIf { it > 0 } ?: 300
            job = scope.launch {
                val bitmap = EvidenceStore.thumbnail(path, sizePx)
                if (boundPath == path) imageThumb.setImageBitmap(bitmap)
            }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private class Diff : DiffUtil.ItemCallback<Evidence>() {
        override fun areItemsTheSame(a: Evidence, b: Evidence) = a.id == b.id
        override fun areContentsTheSame(a: Evidence, b: Evidence) = a == b
    }
}
