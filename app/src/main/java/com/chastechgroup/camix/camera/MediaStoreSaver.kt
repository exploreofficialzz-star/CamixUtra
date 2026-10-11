package com.chastechgroup.camix.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Saves photos / videos into the gallery (Pictures/CamixUltra, Movies/CamixUltra). */
object MediaStoreSaver {

    const val FOLDER = "CamixUltra"

    /** Where a video is being written. Exactly one of (uri + pfd) / file is set. */
    class VideoTarget(val uri: Uri?, val pfd: ParcelFileDescriptor?, val file: File?)

    // ── Photos ────────────────────────────────────────────────────────────────

    /** Encode [bitmap] as JPEG straight into the gallery. */
    fun savePhotoBitmap(ctx: Context, bitmap: Bitmap, displayName: String, quality: Int = 95): Uri? =
        writeImage(ctx, displayName) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }

    /** Copy a JPEG file byte-for-byte (keeps the camera's EXIF, HDR, etc.). */
    fun savePhotoFile(ctx: Context, src: File, displayName: String): Uri? =
        writeImage(ctx, displayName) { out ->
            src.inputStream().use { it.copyTo(out) }
            true
        }

    private fun writeImage(ctx: Context, displayName: String, writer: (OutputStream) -> Boolean): Uri? {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            return try {
                val ok = resolver.openOutputStream(uri)?.use { writer(it) } ?: false
                if (!ok) {
                    resolver.delete(uri, null, null)
                    null
                } else {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    uri
                }
            } catch (t: Throwable) {
                Timber.e(t, "writeImage failed")
                resolver.delete(uri, null, null)
                null
            }
        }

        // API 26-28: write into the public folder and let the media scanner index it
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "$displayName.jpg")
            val ok = FileOutputStream(f).use { writer(it) }
            if (!ok) {
                f.delete()
                null
            } else {
                MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(f)
            }
        } catch (t: Throwable) {
            Timber.e(t, "writeImage (legacy) failed")
            null
        }
    }

    // ── Videos ────────────────────────────────────────────────────────────────

    fun newVideoTarget(ctx: Context, displayName: String): VideoTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw java.io.IOException("MediaStore insert failed")
            // MediaMuxer reads back while finalising the MP4 => read/write mode
            val pfd = resolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                resolver.delete(uri, null, null)
                throw java.io.IOException("openFileDescriptor failed")
            }
            return VideoTarget(uri, pfd, null)
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return VideoTarget(null, null, File(dir, "$displayName.mp4"))
    }

    /** Closes the descriptor and publishes (keep = true) or deletes (keep = false) the video. */
    fun finishVideo(ctx: Context, t: VideoTarget, keep: Boolean): Uri? {
        try { t.pfd?.close() } catch (_: Throwable) { }
        val resolver = ctx.contentResolver
        if (t.uri != null) {
            return if (keep) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(t.uri, done, null, null)
                t.uri
            } else {
                resolver.delete(t.uri, null, null)
                null
            }
        }
        val f = t.file ?: return null
        return if (keep) {
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
            Uri.fromFile(f)
        } else {
            f.delete()
            null
        }
    }
}
