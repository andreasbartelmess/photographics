package de.photographics.gpu

import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.*
import org.lwjgl.egl.EGL12.EGL_OPENGL_ES_API
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EXTPlatformBase.eglGetPlatformDisplayEXT
import org.lwjgl.opengles.GLES

/**
 * OpenGL-ES-3-Kontext ohne Fenster (Tests, Hintergrundrechnen): bevorzugt Mesas „surfaceless“-
 * Plattform, sonst die Standardanzeige. [open] liefert null, wenn kein Treiber verfügbar ist.
 */
class HeadlessEgl private constructor(private val display: Long, private val surface: Long, private val context: Long) : AutoCloseable {
    override fun close() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        eglDestroyContext(display, context)
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface)
        eglTerminate(display)
    }

    companion object {
        private const val EGL_PLATFORM_SURFACELESS_MESA = 0x31DD
        private const val EGL_OPENGL_ES3_BIT = 0x40
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098

        fun open(): HeadlessEgl? = try { create() } catch (e: Throwable) { System.err.println("Kein EGL: $e"); null }

        private fun create(): HeadlessEgl? {
            val clientExt = eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS).orEmpty()
            var display = EGL_NO_DISPLAY
            if ("EGL_MESA_platform_surfaceless" in clientExt && EGL.getCapabilities().EGL_EXT_platform_base)
                display = eglGetPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA, 0L, null as IntArray?)
            if (display == EGL_NO_DISPLAY) display = eglGetDisplay(0L)
            if (display == EGL_NO_DISPLAY) return null
            val major = IntArray(1); val minor = IntArray(1)
            if (!eglInitialize(display, major, minor)) return null
            EGL.createDisplayCapabilities(display, major[0], minor[0])
            if (!eglBindAPI(EGL_OPENGL_ES_API)) return null
            val configs = PointerBuffer.allocateDirect(1); val n = IntArray(1)
            val attribs = intArrayOf(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, 0x3040, EGL_OPENGL_ES3_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE)
            if (!eglChooseConfig(display, attribs, configs, n) || n[0] == 0) return null
            val config = configs.get(0)
            val surface = eglCreatePbufferSurface(display, config, intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE))
            val ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE))
            if (ctx == EGL_NO_CONTEXT) return null
            if (!eglMakeCurrent(display, surface, surface, ctx)) return null
            GLES.createCapabilities()
            return HeadlessEgl(display, surface, ctx)
        }
    }
}
