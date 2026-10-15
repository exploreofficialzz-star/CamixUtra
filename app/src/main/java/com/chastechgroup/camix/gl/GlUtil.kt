package com.chastechgroup.camix.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.chastechgroup.camix.filter.ShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object GlUtil {

    fun checkGlError(op: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw RuntimeException("$op: glError 0x" + Integer.toHexString(e))
    }

    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("createOesTexture")
        return ids[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun identity(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    fun maxTextureSize(): Int {
        val v = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0)
        return if (v[0] > 0) v[0] else 2048
    }
}

/** Colour render target (texture + framebuffer). GL thread only. */
class Fbo(val width: Int, val height: Int) {
    val texture: Int
    val framebuffer: Int

    init {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        framebuffer = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            release()
            throw RuntimeException("Framebuffer incomplete: 0x" + Integer.toHexString(status))
        }
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/** Full-screen quad (triangle strip). Context independent. */
class Quad {
    private val vertices: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    fun draw(prog: ShaderProgram, mvp: FloatArray, st: FloatArray, texTarget: Int, texId: Int) {
        prog.use()
        prog.setMat4("uMVPMatrix", mvp)
        prog.setMat4("uSTMatrix", st)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texId)
        prog.setInt("uTexture", 0)

        val pos = prog.getAttribLocation("aPosition")
        val tex = prog.getAttribLocation("aTextureCoord")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
    }

    private fun floatBuf(a: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a).position(0)
        return b
    }
}

/**
 * Geometry helpers. All formulas were verified numerically against np.rot90 / centre-crop references
 * (see AUDIT.md): camera buffer -> upright frame -> screen.
 */
object GlMath {

    /**
     * Matrix that maps the unit quad onto NDC so a [bufW]x[bufH] camera buffer, rotated clockwise by
     * [rotationDeg] (0/90/180/270), exactly fills an upright frame of rotated dimensions.
     */
    fun cameraToUpright(bufW: Int, bufH: Int, rotationDeg: Int): FloatArray {
        val rot = ((rotationDeg % 360) + 360) % 360
        val swap = rot == 90 || rot == 270
        val dw = (if (swap) bufH else bufW).toFloat()
        val dh = (if (swap) bufW else bufH).toFloat()
        val bw = bufW.toFloat()
        val bh = bufH.toFloat()

        // GL angles are counter-clockwise positive; a clockwise rotation is a negative angle.
        val c: Float
        val s: Float
        when (rot) {
            90   -> { c = 0f;  s = -1f }
            180  -> { c = -1f; s = 0f }
            270  -> { c = 0f;  s = 1f }
            else -> { c = 1f;  s = 0f }
        }
        val m00 = c * bw / dw
        val m01 = -s * bh / dw
        val m10 = s * bw / dh
        val m11 = c * bh / dh

        val m = GlUtil.identity()
        m[0] = m00; m[1] = m10      // column 0
        m[4] = m01; m[5] = m11      // column 1
        return m
    }

    /**
     * What has to happen to a camera frame so it ends up upright.
     *
     * IMPORTANT (verified against the CameraX docs): for a Surface backed by a SurfaceTexture the camera writes
     * its orientation into the buffer, so SurfaceTexture.getTransformMatrix() ALREADY rotates the picture into the
     * device's natural orientation (that is why TextureView "handles this automatically"). The only extra rotation an
     * app has to add is the display rotation = TransformationInfo.rotationDegrees minus the sensor rotation
     * (0 for a portrait-locked phone). Applying rotationDegrees on top of the matrix would turn the picture sideways.
     */
    class OrientationPlan(
        val remainingDegrees: Int,      // extra clockwise rotation WE apply
        val naturalW: Int, val naturalH: Int,   // size after SurfaceTexture.getTransformMatrix()
        val uprightW: Int, val uprightH: Int    // size after our extra rotation
    )

    fun plan(bufW: Int, bufH: Int, infoRotationDegrees: Int, sensorRotationDegrees: Int): OrientationPlan {
        val remaining = (((infoRotationDegrees - sensorRotationDegrees) % 360) + 360) % 360
        val nat = uprightSize(bufW, bufH, sensorRotationDegrees)
        val up = uprightSize(nat[0], nat[1], remaining)
        return OrientationPlan(remaining, nat[0], nat[1], up[0], up[1])
    }

    /**
     * Inverse of a clockwise quarter-turn on normalised coordinates (origin top-left, y down):
     * given a point of the ROTATED image, returns the same point in the image BEFORE the rotation.
     */
    fun undoRotation(u: Float, v: Float, degrees: Int): FloatArray = when (((degrees % 360) + 360) % 360) {
        90   -> floatArrayOf(v, 1f - u)
        180  -> floatArrayOf(1f - u, 1f - v)
        270  -> floatArrayOf(1f - v, u)
        else -> floatArrayOf(u, v)
    }

    /**
     * A point in the upright, un-mirrored frame (what the user sees) -> the same point in the camera's own
     * buffer / sensor orientation, which is what CameraX metering points (SurfaceOrientedMeteringPointFactory)
     * expect. Same maths as CameraX's DisplayOrientedMeteringPointFactory.
     */
    fun frameToBuffer(u: Float, v: Float, remainingDegrees: Int, sensorDegrees: Int): FloatArray {
        val natural = undoRotation(u, v, remainingDegrees)
        return undoRotation(natural[0], natural[1], sensorDegrees)
    }

    /** Dimensions of the upright frame for a camera buffer + rotation. */
    fun uprightSize(bufW: Int, bufH: Int, rotationDeg: Int): IntArray {
        val rot = ((rotationDeg % 360) + 360) % 360
        return if (rot == 90 || rot == 270) intArrayOf(bufH, bufW) else intArrayOf(bufW, bufH)
    }

    /** Centre-crop ("fill") scale of content into a view. Returns (sx, sy), at least one of them 1. */
    fun cropScale(contentW: Int, contentH: Int, viewW: Int, viewH: Int): FloatArray {
        val ca = contentW.toFloat() / contentH.toFloat()
        val va = viewW.toFloat() / viewH.toFloat()
        return if (va > ca) floatArrayOf(1f, va / ca) else floatArrayOf(ca / va, 1f)
    }

    fun scaleMatrix(sx: Float, sy: Float): FloatArray {
        val m = GlUtil.identity()
        m[0] = sx
        m[5] = sy
        return m
    }

    @Suppress("unused")
    fun degToRad(d: Float): Float = (d * PI / 180.0).toFloat()

    @Suppress("unused")
    fun rotZ(deg: Float): FloatArray {
        val r = degToRad(deg)
        val m = GlUtil.identity()
        m[0] = cos(r); m[1] = sin(r); m[4] = -sin(r); m[5] = cos(r)
        return m
    }
}
