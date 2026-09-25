package com.surveyme.presentation.notes

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteComment
import com.surveyme.databinding.ItemDetailTagBinding
import com.surveyme.databinding.ItemNoteCommentBinding
import java.text.SimpleDateFormat
import java.util.Locale

/** One row of the details sheet: a thread entry, a raw OSM tag, or a plain block of text. */
sealed class DetailRow {
    /** [pending] marks an entry built from a queued action that osm.org has not confirmed yet. */
    data class Comment(val comment: NoteComment, val pending: Boolean = false) : DetailRow()
    data class Tag(val key: String, val value: String) : DetailRow()
    data class Text(val text: String) : DetailRow()
}

class NoteDetailsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<DetailRow> = emptyList()
    private val absoluteFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submit(newRows: List<DetailRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DetailRow.Comment -> TYPE_COMMENT
        is DetailRow.Text -> TYPE_TEXT
        is DetailRow.Tag -> TYPE_TAG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TAG -> TagHolder(ItemDetailTagBinding.inflate(inflater, parent, false))
            else -> CommentHolder(ItemNoteCommentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DetailRow.Comment -> (holder as CommentHolder).bind(row.comment, row.pending)
            is DetailRow.Text -> (holder as CommentHolder).bindText(row.text)
            is DetailRow.Tag -> (holder as TagHolder).bind(row)
        }
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

    private companion object {
        const val TYPE_COMMENT = 0
        const val TYPE_TEXT = 1
        const val TYPE_TAG = 2
    }
}
