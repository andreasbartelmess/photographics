package de.photographics.app.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import de.photographics.core.gpu.Gles
import java.nio.ByteBuffer

/** [Gles] über `android.opengl.GLES30` (im aktuellen Kontext des Threads, siehe [HeadlessEgl14]). */
class AndroidGles : Gles {
    private val one = IntArray(1)
    private fun gen(f: (Int, IntArray, Int) -> Unit): Int { f(1, one, 0); return one[0] }

    override fun getString(name: Int): String? = GLES30.glGetString(name)
    override fun getError() = GLES30.glGetError()
    override fun getInteger(name: Int): Int { GLES30.glGetIntegerv(name, one, 0); return one[0] }

    override fun createShader(type: Int) = GLES30.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES30.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES30.glCompileShader(shader)
    override fun getShaderCompiled(shader: Int): Boolean { GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, one, 0); return one[0] != 0 }
    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GLES30.glDeleteShader(shader)

    override fun createProgram() = GLES30.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun linkProgram(program: Int) = GLES30.glLinkProgram(program)
    override fun getProgramLinked(program: Int): Boolean { GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, one, 0); return one[0] != 0 }
    override fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
    override fun getUniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) = GLES30.glUniform1i(location, v)
    override fun uniform2i(location: Int, x: Int, y: Int) = GLES30.glUniform2i(location, x, y)
    override fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) = GLES30.glUniform4i(location, x, y, z, w)
    override fun uniform1f(location: Int, v: Float) = GLES30.glUniform1f(location, v)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(location, x, y, z, w)

    override fun genTexture() = gen(GLES30::glGenTextures)
    override fun deleteTexture(texture: Int) { one[0] = texture; GLES30.glDeleteTextures(1, one, 0) }
    override fun activeTexture(unit: Int) = GLES30.glActiveTexture(unit)
    override fun bindTexture(target: Int, texture: Int) = GLES30.glBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES30.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer?) =
        GLES30.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, data)
    override fun texImage3D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteBuffer?) =
        GLES30.glTexImage3D(target, level, internalFormat, width, height, depth, 0, format, type, data)
    override fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES30.glTexSubImage3D(target, level, x, y, z, width, height, depth, format, type, data)
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES30.glTexSubImage2D(target, level, x, y, width, height, format, type, data)
    override fun pixelStorei(name: Int, value: Int) = GLES30.glPixelStorei(name, value)

    override fun genFramebuffer() = gen(GLES30::glGenFramebuffers)
    override fun deleteFramebuffer(fb: Int) { one[0] = fb; GLES30.glDeleteFramebuffers(1, one, 0) }
    override fun bindFramebuffer(target: Int, fb: Int) = GLES30.glBindFramebuffer(target, fb)
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) =
        GLES30.glFramebufferTexture2D(target, attachment, texTarget, texture, level)
    override fun checkFramebufferStatus(target: Int) = GLES30.glCheckFramebufferStatus(target)

    override fun genVertexArray() = gen(GLES30::glGenVertexArrays)
    override fun bindVertexArray(vao: Int) = GLES30.glBindVertexArray(vao)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES30.glViewport(x, y, width, height)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun disable(cap: Int) = GLES30.glDisable(cap)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES30.glReadPixels(x, y, width, height, format, type, data)
    override fun finish() = GLES30.glFinish()
}

/** OpenGL-ES-3-Kontext ohne Fenster (1×1-Pbuffer) für den Engine-Thread. */
class HeadlessEgl14 private constructor(private val display: EGLDisplay, private val surface: EGLSurface, private val context: EGLContext) : AutoCloseable {
    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40

        fun open(): HeadlessEgl14? {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (d == EGL14.EGL_NO_DISPLAY) return null
            val v = IntArray(2)
            if (!EGL14.eglInitialize(d, v, 0, v, 1)) return null
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) return null
            val cfg = configs[0] ?: return null
            val surface = EGL14.eglCreatePbufferSurface(d, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            val ctx = EGL14.eglCreateContext(d, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE || ctx == EGL14.EGL_NO_CONTEXT) return null
            if (!EGL14.eglMakeCurrent(d, surface, surface, ctx)) return null
            return HeadlessEgl14(d, surface, ctx)
        }
    }
}
