package dev.mark.lineover.feature.editor.canvas

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the preview as one textured quad (GLES 3.0). Everything the draw loop needs is
 * allocated up front: [onDrawFrame] only copies the latest matrix and issues GL calls.
 * Stage 4 replaces this with the engine's tiled renderer; the view and gestures stay.
 */
internal class CanvasRenderer : GLSurfaceView.Renderer {
    private val lock = Any()

    // Written by the UI thread under [lock]; read by the GL thread.
    private val pendingMatrix = FloatArray(MATRIX_SIZE)
    private var pendingBitmap: Bitmap? = null
    private var bitmapChanged = false
    private var surroundR = 0f
    private var surroundG = 0f
    private var surroundB = 0f

    // GL thread only.
    private val drawMatrix = FloatArray(MATRIX_SIZE)
    private var program = 0
    private var matrixLocation = 0
    private var texture = 0
    private var hasTexture = false
    private val quad: ByteBuffer =
        ByteBuffer.allocateDirect(QUAD.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(QUAD)
        }

    /** Frame timing, for the instrumented frame-timing test and debugging. */
    val stats = FrameStats()

    fun setMatrix(matrix: FloatArray) {
        synchronized(lock) { matrix.copyInto(pendingMatrix) }
    }

    fun setBitmap(bitmap: Bitmap?) {
        synchronized(lock) {
            pendingBitmap = bitmap
            bitmapChanged = true
        }
    }

    fun setSurround(argb: Int) {
        synchronized(lock) {
            surroundR = ((argb shr RED_SHIFT) and BYTE_MASK) / BYTE_MAX
            surroundG = ((argb shr GREEN_SHIFT) and BYTE_MASK) / BYTE_MAX
            surroundB = (argb and BYTE_MASK) / BYTE_MAX
        }
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        matrixLocation = GLES30.glGetUniformLocation(program, "uMatrix")
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        hasTexture = false
        // A new EGL context has no texture: upload the current bitmap again.
        synchronized(lock) { bitmapChanged = pendingBitmap != null }
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val start = System.nanoTime()
        var upload: Bitmap? = null
        var r: Float
        var g: Float
        var b: Float
        synchronized(lock) {
            pendingMatrix.copyInto(drawMatrix)
            if (bitmapChanged) {
                upload = pendingBitmap
                bitmapChanged = false
                if (upload == null) hasTexture = false
            }
            r = surroundR
            g = surroundG
            b = surroundB
        }
        upload?.let(::uploadTexture)
        GLES30.glClearColor(r, g, b, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (hasTexture) {
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix3fv(matrixLocation, 1, false, drawMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            quad.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, QUAD.size / 2)
        }
        stats.record(start, System.nanoTime())
    }

    /** One-time upload when the preview changes (not per frame). */
    private fun uploadTexture(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        hasTexture = true
    }

    private fun linkProgram(
        vertex: String,
        fragment: String,
    ): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glBindAttribLocation(program, 0, "aPosition")
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "canvas shader link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun compile(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "canvas shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val MATRIX_SIZE = 9
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BYTE_MASK = 0xFF
        const val BYTE_MAX = 255f

        /** Unit quad as a triangle strip; (0,0) is the top-left of the photo. */
        val QUAD = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPosition;
uniform mat3 uMatrix;
out vec2 vTexCoord;
void main() {
    vec3 p = uMatrix * vec3(aPosition, 1.0);
    gl_Position = vec4(p.xy, 0.0, 1.0);
    vTexCoord = aPosition;
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in vec2 vTexCoord;
out vec4 fragColor;
void main() {
    fragColor = texture(uTexture, vTexCoord);
}
"""
    }
}

/**
 * Ring buffer of the last [CAPACITY] frames: interval between frame starts and GL draw
 * time, in nanoseconds. Recording never allocates.
 */
class FrameStats {
    private val intervals = LongArray(CAPACITY)
    private val draws = LongArray(CAPACITY)
    private var lastStart = 0L
    private var next = 0

    @Volatile var frames = 0
        private set

    @Synchronized
    fun record(
        start: Long,
        end: Long,
    ) {
        intervals[next] = if (lastStart == 0L) 0L else start - lastStart
        draws[next] = end - start
        lastStart = start
        next = (next + 1) % CAPACITY
        frames++
    }

    /** Forget earlier frames (e.g. before a measured gesture). */
    @Synchronized
    fun reset() {
        intervals.fill(0L)
        draws.fill(0L)
        lastStart = 0L
        next = 0
        frames = 0
    }

    /** 95th percentile of the frame intervals in ms (0 when fewer than two frames). */
    @Synchronized
    fun p95IntervalMillis(): Double = percentile(intervals.filter { it > 0 })

    /** 95th percentile of the GL draw time in ms. */
    @Synchronized
    fun p95DrawMillis(): Double = percentile(draws.filter { it > 0 })

    private fun percentile(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * P95).toInt()
        return sorted[index] / NANOS_PER_MILLI
    }

    companion object {
        const val CAPACITY = 600
        private const val P95 = 0.95
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
