package ch.madtreasures.g2watch.apps.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import ch.madtreasures.g2watch.desktop.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Takes the decoder's pictures off the GPU (03 §10). The decoder draws into a [SurfaceTexture]; for a
 * picture that is due, [read] draws it scaled to the raster grid, as luma, into a small offscreen buffer
 * and reads that back: a few ten kilobytes instead of a whole video picture. This works with every
 * hardware decoder, unlike an ImageReader, which many Qualcomm decoders cannot feed (they use their own
 * YUV layouts). Everything, [onPicture] included, runs on [handler]'s thread; create it there.
 */
internal class GlFrameGrabber(private val handler: Handler, private val onPicture: () -> Unit) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private var target: EGLSurface = EGL14.EGL_NO_SURFACE
    private var targetW = 0
    private var targetH = 0
    private val texture: Int
    private val program: Int
    private val surfaceTexture: SurfaceTexture
    private val matrix = FloatArray(16)
    private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4)
    private var released = false

    /** Where the decoder draws; hand it to the player. */
    val surface: Surface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        resize(1, 1)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = link(VERTEX, FRAGMENT)

        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setOnFrameAvailableListener({ if (!released) picture() }, handler)
        surface = Surface(surfaceTexture)
    }

    private fun picture() {
        // Always take the picture off the queue, or the decoder stalls; draw it only when asked.
        surfaceTexture.updateTexImage()
        onPicture()
    }

    /**
     * Draws the latest picture into [picture] of a [gridW] × [gridH] grid, black around it, and writes
     * its luma row by row (top first) into [out].
     */
    fun read(gridW: Int, gridH: Int, picture: Rect, out: ByteArray) {
        if (released) return
        resize(gridW, gridH)
        GLES20.glViewport(0, 0, gridW, gridH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // GL counts rows from the bottom.
        GLES20.glViewport(picture.x, gridH - picture.bottom, picture.w, picture.h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        surfaceTexture.getTransformMatrix(matrix)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)
        // Four taps a quarter cell from the centre: roughly the average over the raster point.
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uStep"), 0.25f / picture.w, 0.25f / picture.h)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
        pixels.clear()
        GLES20.glReadPixels(0, 0, gridW, gridH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        for (y in 0 until gridH) {
            val src = (gridH - 1 - y) * gridW * 4
            val dst = y * gridW
            for (x in 0 until gridW) out[dst + x] = pixels.get(src + x * 4)
        }
    }

    /** An offscreen target of [w] × [h], made current. */
    private fun resize(w: Int, h: Int) {
        if (w == targetW && h == targetH && target != EGL14.EGL_NO_SURFACE) return
        if (target != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, target)
        }
        target = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, target, target, context)) { "eglMakeCurrent failed" }
        targetW = w
        targetH = h
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    fun release() {
        if (released) return
        released = true
        surfaceTexture.setOnFrameAvailableListener(null)
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, target)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        val QUAD: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val TEX: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }

        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform vec2 uStep;
            varying vec2 vTex;
            void main() {
                vec3 c = texture2D(uTex, vTex + vec2(-uStep.x, -uStep.y)).rgb
                       + texture2D(uTex, vTex + vec2(uStep.x, -uStep.y)).rgb
                       + texture2D(uTex, vTex + vec2(-uStep.x, uStep.y)).rgb
                       + texture2D(uTex, vTex + uStep).rgb;
                float y = dot(c * 0.25, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(y, y, y, 1.0);
            }
        """
    }
}
