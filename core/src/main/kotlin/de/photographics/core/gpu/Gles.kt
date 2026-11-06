package de.photographics.core.gpu

import java.nio.ByteBuffer

/**
 * Der Ausschnitt von OpenGL ES 3.0, den der [GpuCompositor] braucht. Android bildet ihn auf
 * `android.opengl.GLES30` ab, der Desktop (GNOME, Tests) auf LWJGL. Alle Aufrufe laufen auf dem
 * Thread, dem der GL-Kontext gehört.
 *
 * Puffer sind direkte [ByteBuffer] in nativer Byte-Reihenfolge; Position und Limit bestimmen den
 * Ausschnitt.
 */
interface Gles {
    fun getString(name: Int): String?
    fun getError(): Int
    fun getInteger(name: Int): Int

    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun getShaderCompiled(shader: Int): Boolean
    fun getShaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)

    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgramLinked(program: Int): Boolean
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun deleteProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, v: Int)
    fun uniform2i(location: Int, x: Int, y: Int)
    fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int)
    fun uniform1f(location: Int, v: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)

    fun genTexture(): Int
    fun deleteTexture(texture: Int)
    fun activeTexture(unit: Int)
    fun bindTexture(target: Int, texture: Int)
    fun texParameteri(target: Int, name: Int, value: Int)
    fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer?)
    fun texImage3D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteBuffer?)
    fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteBuffer)
    fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer)
    fun pixelStorei(name: Int, value: Int)

    fun genFramebuffer(): Int
    fun deleteFramebuffer(fb: Int)
    fun bindFramebuffer(target: Int, fb: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int)
    fun checkFramebufferStatus(target: Int): Int

    fun genVertexArray(): Int
    fun bindVertexArray(vao: Int)

    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun disable(cap: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer)
    fun finish()

    /** Die benötigten Zahlenwerte aus dem GLES-3.0-Header. */
    object C {
        const val NO_ERROR = 0
        const val EXTENSIONS = 0x1F03
        const val VERSION = 0x1F02
        const val RENDERER = 0x1F01
        const val MAX_TEXTURE_SIZE = 0x0D33
        const val VERTEX_SHADER = 0x8B31
        const val FRAGMENT_SHADER = 0x8B30
        const val TEXTURE_2D = 0x0DE1
        const val TEXTURE_3D = 0x806F
        const val TEXTURE_WRAP_R = 0x8072
        const val TEXTURE0 = 0x84C0
        const val TEXTURE_MIN_FILTER = 0x2801
        const val TEXTURE_MAG_FILTER = 0x2800
        const val TEXTURE_WRAP_S = 0x2802
        const val TEXTURE_WRAP_T = 0x2803
        const val NEAREST = 0x2600
        const val LINEAR = 0x2601
        const val CLAMP_TO_EDGE = 0x812F
        const val RGBA = 0x1908
        const val RGBA8 = 0x8058
        const val RGBA16F = 0x881A
        const val RGBA32F = 0x8814
        const val R8 = 0x8229
        const val RED = 0x1903
        const val UNSIGNED_BYTE = 0x1401
        const val HALF_FLOAT = 0x140B
        const val FLOAT = 0x1406
        const val UNPACK_ALIGNMENT = 0x0CF5
        const val PACK_ALIGNMENT = 0x0D05
        const val FRAMEBUFFER = 0x8D40
        const val COLOR_ATTACHMENT0 = 0x8CE0
        const val FRAMEBUFFER_COMPLETE = 0x8CD5
        const val COLOR_BUFFER_BIT = 0x4000
        const val BLEND = 0x0BE2
        const val SCISSOR_TEST = 0x0C11
        const val DEPTH_TEST = 0x0B71
        const val TRIANGLE_STRIP = 0x0005
    }
}
