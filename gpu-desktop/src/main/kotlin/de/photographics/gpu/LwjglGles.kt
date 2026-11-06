package de.photographics.gpu

import de.photographics.core.gpu.Gles
import org.lwjgl.opengles.GLES20
import org.lwjgl.opengles.GLES30
import java.nio.ByteBuffer

/** [Gles] über LWJGL (OpenGL ES im aktuellen Kontext des Threads: GtkGLArea oder [HeadlessEgl]). */
class LwjglGles : Gles {
    override fun getString(name: Int): String? = GLES20.glGetString(name)
    override fun getError() = GLES20.glGetError()
    override fun getInteger(name: Int) = GLES20.glGetInteger(name)

    override fun createShader(type: Int) = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun getShaderCompiled(shader: Int) = GLES20.glGetShaderi(shader, GLES20.GL_COMPILE_STATUS) != 0
    override fun getShaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GLES20.glDeleteShader(shader)

    override fun createProgram() = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun getProgramLinked(program: Int) = GLES20.glGetProgrami(program, GLES20.GL_LINK_STATUS) != 0
    override fun getProgramInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun getUniformLocation(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) = GLES20.glUniform1i(location, v)
    override fun uniform2i(location: Int, x: Int, y: Int) = GLES20.glUniform2i(location, x, y)
    override fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) = GLES20.glUniform4i(location, x, y, z, w)
    override fun uniform1f(location: Int, v: Float) = GLES20.glUniform1f(location, v)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = GLES20.glUniform4f(location, x, y, z, w)

    override fun genTexture() = GLES20.glGenTextures()
    override fun deleteTexture(texture: Int) = GLES20.glDeleteTextures(texture)
    override fun activeTexture(unit: Int) = GLES20.glActiveTexture(unit)
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES20.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer?) =
        GLES20.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, data)
    override fun texImage3D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteBuffer?) =
        GLES30.glTexImage3D(target, level, internalFormat, width, height, depth, 0, format, type, data)
    override fun texSubImage3D(target: Int, level: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES30.glTexSubImage3D(target, level, x, y, z, width, height, depth, format, type, data)
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES20.glTexSubImage2D(target, level, x, y, width, height, format, type, data)
    override fun pixelStorei(name: Int, value: Int) = GLES20.glPixelStorei(name, value)

    override fun genFramebuffer() = GLES20.glGenFramebuffers()
    override fun deleteFramebuffer(fb: Int) = GLES20.glDeleteFramebuffers(fb)
    override fun bindFramebuffer(target: Int, fb: Int) = GLES20.glBindFramebuffer(target, fb)
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) =
        GLES20.glFramebufferTexture2D(target, attachment, texTarget, texture, level)
    override fun checkFramebufferStatus(target: Int) = GLES20.glCheckFramebufferStatus(target)

    override fun genVertexArray() = GLES30.glGenVertexArrays()
    override fun bindVertexArray(vao: Int) = GLES30.glBindVertexArray(vao)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES20.glViewport(x, y, width, height)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES20.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES20.glClear(mask)
    override fun disable(cap: Int) = GLES20.glDisable(cap)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES20.glDrawArrays(mode, first, count)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, data: ByteBuffer) =
        GLES20.glReadPixels(x, y, width, height, format, type, data)
    override fun finish() = GLES20.glFinish()
}
