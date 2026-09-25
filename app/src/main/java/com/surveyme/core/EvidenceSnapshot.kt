package com.surveyme.core

/**
 * Pure helpers for evidence (item 11), kept free of `android.*` so plain JUnit runs them.
 */
object EvidenceSnapshot {
    /** How much of the item's text the evidence row keeps: enough to recognise it at a desk. */
    const val SUMMARY_MAX = 200

    /**
     * The first non-blank line of [text], trimmed and cut to [max] characters with an ellipsis;
     * "" for blank input. The snapshot the review list shows once the POI is gone.
     */
    fun summaryOf(text: String?, max: Int = SUMMARY_MAX): String {
        val line = text.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }

    /**
     * The largest power-of-two `inSampleSize` that still decodes [width] x [height] to at least
     * [reqWidth] x [reqHeight] (BitmapFactory's contract: the decoder only honours powers of two
     * and rounds others down). 1 when the image already fits or a dimension is unknown.
     */
    fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var size = 1
        while (height / (size * 2) >= reqHeight && width / (size * 2) >= reqWidth) size *= 2
        return size
    }
}
