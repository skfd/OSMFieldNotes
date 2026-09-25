package com.surveyme.presentation.notes

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.surveyme.data.EvidenceStore
import com.surveyme.data.model.Evidence
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteComment
import com.surveyme.databinding.ItemDetailTagBinding
import com.surveyme.databinding.ItemEvidenceBinding
import com.surveyme.databinding.ItemNoteCommentBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One row of the details sheet: a thread entry, a raw OSM tag, a plain block of text, a
 * section header, or a piece of evidence pinned to the item (item 11).
 */
sealed class DetailRow {
    /** [pending] marks an entry built from a queued action that osm.org has not confirmed yet. */
    data class Comment(val comment: NoteComment, val pending: Boolean = false) : DetailRow()
    data class Tag(val key: String, val value: String) : DetailRow()
    data class Text(val text: String) : DetailRow()
    data class Header(val text: String) : DetailRow()
    data class EvidenceItem(val evidence: Evidence) : DetailRow()
}

class NoteDetailsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<DetailRow> = emptyList()
    private val absoluteFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** Where thumbnails are decoded; the view lifecycle's scope, set by the fragment. Null shows no thumbnails. */
    var thumbnailScope: CoroutineScope? = null

    /** A tap on an evidence row (the fragment opens the photo). */
    var onEvidenceClick: (Evidence) -> Unit = {}

    fun submit(newRows: List<DetailRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DetailRow.Comment -> TYPE_COMMENT
        is DetailRow.Text -> TYPE_TEXT
        is DetailRow.Tag -> TYPE_TAG
        is DetailRow.Header -> TYPE_HEADER
        is DetailRow.EvidenceItem -> TYPE_EVIDENCE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TAG -> TagHolder(ItemDetailTagBinding.inflate(inflater, parent, false))
            TYPE_EVIDENCE -> EvidenceHolder(ItemEvidenceBinding.inflate(inflater, parent, false))
            else -> CommentHolder(ItemNoteCommentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DetailRow.Comment -> (holder as CommentHolder).bind(row.comment, row.pending)
            is DetailRow.Text -> (holder as CommentHolder).bindText(row.text)
            is DetailRow.Header -> (holder as CommentHolder).bindHeader(row.text)
            is DetailRow.Tag -> (holder as TagHolder).bind(row)
            is DetailRow.EvidenceItem -> (holder as EvidenceHolder).bind(row.evidence)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? EvidenceHolder)?.cancel()
    }

    inner class CommentHolder(private val binding: ItemNoteCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: NoteComment, pending: Boolean) {
            val who = comment.author ?: "Anonymous"
            val verb = when (comment.action) {
                NoteAction.OPENED -> "opened"
                NoteAction.COMMENTED -> "commented"
                NoteAction.CLOSED -> "closed"
                NoteAction.REOPENED -> "reopened"
                NoteAction.HIDDEN -> "hidden"
            }
            binding.textHeader.visibility = View.VISIBLE
            binding.textHeader.text = if (pending) "$who $verb · pending" else "$who $verb · ${relativeDate(comment.date.time)}"
            binding.root.alpha = if (pending) 0.6f else 1f
            if (comment.text.isBlank()) {
                binding.textBody.visibility = View.GONE
            } else {
                binding.textBody.visibility = View.VISIBLE
                binding.textBody.text = comment.text
            }
        }

        fun bindText(text: String) {
            binding.root.alpha = 1f
            binding.textHeader.visibility = View.GONE
            binding.textBody.visibility = View.VISIBLE
            binding.textBody.text = text
        }

        /** A section title such as "Evidence (2)": the bold caption line alone. */
        fun bindHeader(text: String) {
            binding.root.alpha = 1f
            binding.textHeader.visibility = View.VISIBLE
            binding.textHeader.text = text
            binding.textBody.visibility = View.GONE
        }

        /** "3 days ago" this week; otherwise a short absolute date. */
        private fun relativeDate(time: Long): CharSequence {
            val now = System.currentTimeMillis()
            return if (now - time < DateUtils.WEEK_IN_MILLIS) {
                DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS)
            } else {
                absoluteFormat.format(time)
            }
        }
    }

    inner class TagHolder(private val binding: ItemDetailTagBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DetailRow.Tag) {
            binding.textKey.text = row.key
            binding.textValue.text = row.value
        }
    }

    /**
     * A pinned photo or text (item 11). The thumbnail is decoded off the main thread by
     * [EvidenceStore]; a rebind cancels the previous decode so a recycled holder never shows
     * another row's picture.
     */
    inner class EvidenceHolder(private val binding: ItemEvidenceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null
        private var current: Evidence? = null

        init {
            binding.root.setOnClickListener { current?.let(onEvidenceClick) }
        }

        fun bind(evidence: Evidence) {
            cancel()
            current = evidence
            val kind = if (evidence.isPhoto) "Photo" else "Text"
            binding.textCaption.text = buildString {
                append(kind).append(" · ").append(absoluteFormat.format(evidence.createdAt))
                if (evidence.reviewed) append(" · reviewed")
            }
            binding.root.alpha = if (evidence.reviewed) 0.6f else 1f
            val text = evidence.text?.takeIf { it.isNotBlank() }
            binding.textBody.visibility = if (text != null) View.VISIBLE else View.GONE
            binding.textBody.text = text
            val path = evidence.photoPath
            if (path == null) {
                binding.imageThumb.visibility = View.GONE
                binding.imageThumb.setImageBitmap(null)
                return
            }
            binding.imageThumb.visibility = View.VISIBLE
            binding.imageThumb.setImageBitmap(null)
            val sizePx = binding.imageThumb.layoutParams.width.takeIf { it > 0 } ?: 200
            job = thumbnailScope?.launch {
                val bitmap = EvidenceStore.thumbnail(path, sizePx)
                if (current?.photoPath == path) binding.imageThumb.setImageBitmap(bitmap)
            }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private companion object {
        const val TYPE_COMMENT = 0
        const val TYPE_TEXT = 1
        const val TYPE_TAG = 2
        const val TYPE_HEADER = 3
        const val TYPE_EVIDENCE = 4
    }
}
