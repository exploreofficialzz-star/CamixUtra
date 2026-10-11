package com.chastechgroup.camix.filter

import android.opengl.GLES20
import timber.log.Timber

/**
 * Wraps an OpenGL ES 2.0 program (vertex + fragment shader).
 * Must be created and used on the GL thread that owns the current EGL context.
 * Uniform / attribute locations are cached (glGetUniformLocation is a string lookup).
 */
class ShaderProgram(vertexSource: String, fragmentSource: String) {

    private val programHandle: Int
    private val uniforms = HashMap<String, Int>()
    private val attribs  = HashMap<String, Int>()

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER,   vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        programHandle = linkProgram(vs, fs)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun use() = GLES20.glUseProgram(programHandle)

    fun release() {
        GLES20.glDeleteProgram(programHandle)
        uniforms.clear(); attribs.clear()
    }

    // ── Attribute / Uniform locations ─────────────────────────────────────────

    fun getAttribLocation(name: String): Int =
        attribs.getOrPut(name) { GLES20.glGetAttribLocation(programHandle, name) }

    private fun uniformLocation(name: String): Int =
        uniforms.getOrPut(name) { GLES20.glGetUniformLocation(programHandle, name) }

    fun setFloat(name: String, value: Float) {
        val loc = uniformLocation(name)
        if (loc >= 0) GLES20.glUniform1f(loc, value)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val loc = uniformLocation(name)
        if (loc >= 0) GLES20.glUniform2f(loc, x, y)
    }

    fun setInt(name: String, value: Int) {
        val loc = uniformLocation(name)
        if (loc >= 0) GLES20.glUniform1i(loc, value)
    }

    fun setMat4(name: String, matrix: FloatArray) {
        val loc = uniformLocation(name)
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, matrix, 0)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed (type=$type)" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader compile error: $log")
        }
        return shader
    }

    private fun linkProgram(vs: Int, fs: Int): Int {
        val prog = GLES20.glCreateProgram()
        check(prog != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            error("Program link error: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        Timber.d("ShaderProgram linked OK (handle=$prog)")
        return prog
    }

    companion object {
        /** Shared vertex shader — identical for all programs */
        const val VERTEX_SHADER_DEFAULT = CameraFilterShaders.VERTEX_SHADER_DEFAULT
    }
}
