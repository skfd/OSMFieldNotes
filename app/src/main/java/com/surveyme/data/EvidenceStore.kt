package com.surveyme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import com.surveyme.core.EvidenceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * The files behind photo evidence (item 11): JPEGs under the app's private `files/evidence/`
 * directory, never on shared storage (no permission needed, gone with an uninstall, outside
 * the backup which only includes shared prefs). Shared with the camera app and with viewers
 * through the FileProvider declared in the manifest, and decoded down to thumbnails with
 * `inSampleSize` so a list of 12 MP shots does not run out of memory.
 */
object EvidenceStore {
    private const val DIR = "evidence"

    /** A handful of thumbnails at a time; a bitmap of ~250x250 ARGB is ~250 KB. */
    private val thumbnails = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** A fresh, empty file for the camera to write into. */
    fun newPhotoFile(context: Context): File = File(dir(context), "evidence_${System.currentTimeMillis()}.jpg")

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** Copies a picked image into the store; null when the stream could not be read. */
    suspend fun importPhoto(context: Context, open: () -> InputStream?): File? = withContext(Dispatchers.IO) {
        val target = newPhotoFile(context)
        runCatching {
            val input = open() ?: return@withContext null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            target.takeIf { it.length() > 0 }
        }.onFailure {
            Timber.e(it, "Importing picked photo")
            target.delete()
        }.getOrNull()
    }

    fun deleteFile(path: String?) {
        if (path == null) return
        thumbnails.remove(path)
        val file = File(path)
        if (file.exists() && !file.delete()) Timber.w("Could not delete $path")
    }

    /**
     * A thumbnail at most about [sizePx] on its shorter side, decoded off the main thread and
     * rotated per the JPEG's EXIF orientation (camera apps write portrait shots landscape plus
     * a flag). Null when the file is missing or unreadable.
     */
    suspend fun thumbnail(path: String, sizePx: Int): Bitmap? {
        thumbnails.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            decode(path, sizePx)?.also { thumbnails.put(path, it) }
        }
    }

    private fun decode(path: String, sizePx: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = EvidenceSnapshot.sampleSize(bounds.outWidth, bounds.outHeight, sizePx, sizePx)
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(path, options) }
            .onFailure { Timber.w(it, "Decoding $path") }
            .getOrNull() ?: return null
        return rotateForExif(bitmap, path)
    }

    private fun rotateForExif(bitmap: Bitmap, path: String): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
