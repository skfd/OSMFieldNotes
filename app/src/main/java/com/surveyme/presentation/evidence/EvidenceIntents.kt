package com.surveyme.presentation.evidence

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.surveyme.R
import com.surveyme.data.EvidenceStore
import com.surveyme.data.model.Evidence
import timber.log.Timber
import java.io.File

/**
 * The two ways evidence leaves the app (item 11): a photo opened in whatever viewer the
 * device has, and a photo or text handed to a share target. Both go through the FileProvider
 * URI with a read grant; nothing is uploaded by this app itself.
 */
object EvidenceIntents {

    /** What a row is about, for captions: "Note 12345", "Pending note", "Fixme". */
    fun itemLabel(evidence: Evidence): String = when {
        evidence.noteId != null && evidence.noteId > 0 -> "Note ${evidence.noteId}"
        evidence.noteId != null -> "Pending note"
        evidence.itemSource == "osm_fixme" -> "Fixme"
        else -> "Item"
    }

    /** Opens the photo full screen in an image viewer; a text row has nothing to open. */
    fun view(context: Context, evidence: Evidence) {
        val path = evidence.photoPath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, R.string.evidence_photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(EvidenceStore.uriFor(context, file), "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Timber.w(it, "No viewer for $path")
            Toast.makeText(context, R.string.evidence_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    /** ACTION_SEND with the photo (plus the caption) or the text alone, through a chooser. */
    fun share(context: Context, evidence: Evidence) {
        val caption = buildString {
            append(itemLabel(evidence)).append(": ").append(evidence.itemTitle)
            if (evidence.itemSummary.isNotBlank() && evidence.itemSummary != evidence.itemTitle) append("\n").append(evidence.itemSummary)
            evidence.text?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        }
        val file = evidence.photoPath?.let(::File)?.takeIf { it.exists() }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "${itemLabel(evidence)}: ${evidence.itemTitle}")
            putExtra(Intent.EXTRA_TEXT, caption)
            if (file != null) {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, EvidenceStore.uriFor(context, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.evidence_share_chooser)))
        }.onFailure {
            Timber.w(it, "Nothing to share evidence ${evidence.id} with")
            Toast.makeText(context, R.string.evidence_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }
}
