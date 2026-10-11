package com.chastechgroup.camix.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.exifinterface.media.ExifInterface
import com.chastechgroup.camix.filter.FilterParameters
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Applies the live look (master + style) to a captured JPEG on the GPU at (up to) 4096 px,
 * so "what you see is what you get". Replaces the old CPU engine that down-scaled every photo
 * to ~1.5 MP and wrote it without EXIF rotation.
 *
 * Blocking – call from a background thread. One render at a time.
 */
class StillRenderer {

    private val lock = Any()

    /**
     * @return upright ARGB_8888 bitmap with the look applied, or null when nothing had to be done / it failed
     *         (caller then keeps the untouched original).
     */
    fun render(file: File, params: FilterParameters): Bitmap? {
        return synchronized(lock) { renderLocked(file, params) }
    }

    private fun renderLocked(file: File, params: FilterParameters): Bitmap? {
        var egl: EglCore? = null
        var srcTex = 0
        var fa: Fbo? = null
        var fb: Fbo? = null
        val look = LookPass()
        try {
            egl = EglCore(recordable = false, pbufferOnly = true)
            if (!egl.makeIdleCurrent()) return null

            val maxEdge = min(GlUtil.maxTextureSize(), 4096)
            val bmp = decodeUpright(file, maxEdge) ?: return null
            val w = bmp.width
            val h = bmp.height

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            srcTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            GlUtil.checkGlError("upload photo")

            fa = Fbo(w, h)
            fb = Fbo(w, h)
            val quad = Quad()

            // shaders measure neighbour offsets in "preview pixels" so strengths match the viewfinder
            val portrait = h >= w
            val refW = if (portrait) 1080f else 1920f
            val refH = if (portrait) 1920f else 1080f

            val tex = look.render(srcTex, fa, fb, params, refW, refH, 0.5f, quad)
            if (tex == srcTex) return null                       // nothing rendered

            val src = if (tex == fa.texture) fa else fb
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, src.framebuffer)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GlUtil.checkGlError("readPixels")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            out.copyPixelsFromBuffer(buf)
            return out
        } catch (t: Throwable) {            // includes OutOfMemoryError
            Timber.e(t, "StillRenderer failed")
            return null
        } finally {
            try { fa?.release() } catch (_: Throwable) { }
            try { fb?.release() } catch (_: Throwable) { }
            try { if (srcTex != 0) GLES20.glDeleteTextures(1, intArrayOf(srcTex), 0) } catch (_: Throwable) { }
            try { look.release() } catch (_: Throwable) { }
            try { egl?.release() } catch (_: Throwable) { }
        }
    }

    companion object {
        /** Decode with the longest edge <= [maxEdge] and EXIF rotation / flip applied. */
        fun decodeUpright(file: File, maxEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            while (longEdge / sample > maxEdge) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

            val orientation = try {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180      -> m.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> { m.setRotate(180f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE       -> { m.setRotate(90f);  m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90       -> m.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE      -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270      -> m.setRotate(-90f)
                else -> return decoded
            }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (rotated !== decoded) decoded.recycle()
            return rotated
        }
    }
}
