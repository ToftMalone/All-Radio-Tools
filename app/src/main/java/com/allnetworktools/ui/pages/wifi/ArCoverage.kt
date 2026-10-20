package com.allnetworktools.ui.pages.wifi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** What the phone offers for AR mapping. */
data class ArSupport(val arcore: ArCoreApk.Availability?, val tofCamera: Boolean) {
    val possible get() = arcore?.isSupported == true
}

object ArCoverage {
    fun support(context: Context): ArSupport = ArSupport(
        runCatching { ArCoreApk.getInstance().checkAvailability(context) }.getOrNull(),
        hasTof(context),
    )

    /** A camera that outputs depth maps: the time-of-flight (infrared) sensor of phones that have one. */
    fun hasTof(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        cm.cameraIdList.any { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) == true
        }
    }.getOrDefault(false)

    /**
     * An ARCore session tracking the world, with depth on when the phone can (from its ToF sensor when it has one,
     * otherwise estimated from the camera's motion). Returns the session and whether the ToF sensor is in use.
     */
    fun session(context: Context): Pair<Session, Boolean> {
        val session = Session(context)
        // Prefer a camera configuration that feeds ARCore the hardware depth sensor.
        val tofConfigs = session.getSupportedCameraConfigs(
            CameraConfigFilter(session).setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)),
        )
        val tof = tofConfigs.isNotEmpty()
        if (tof) session.cameraConfig = tofConfigs.first()
        session.configure(
            Config(session).apply {
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            },
        )
        return session to tof
    }

    fun Context.activity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/** Where the camera is, reported to the UI about ten times a second. */
data class ArPose(val x: Float, val z: Float, val floorY: Float, val headingRad: Float, val tracking: TrackingState, val failure: TrackingFailureReason, val depth: Boolean)

/**
 * Draws the camera image dimmed, and on the floor the painted tiles (see PaintedFloor) with the phone's position as a
 * blue dot and arrow, the way WiFiman's AR signal map looks. Runs on the GL thread; [tiles] is replaced from the UI.
 */
class ArCoverageRenderer(
    private val session: Session,
    private val rotation: () -> Int,
    private val onPose: (ArPose) -> Unit,
) : GLSurfaceView.Renderer {
    /** Interleaved x, y, z, r, g, b, a per vertex, six vertices per tile. */
    @Volatile var tiles: FloatArray = FloatArray(0)

    private val main = Handler(Looper.getMainLooper())
    private var cameraTex = 0
    private var bgProgram = 0
    private var flatProgram = 0
    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadUv = floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private var tileBuffer: FloatBuffer = floats()
    private var tileSource: FloatArray? = null
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private var floorY = Float.NaN
    private var lastReport = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        cameraTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session.setCameraTextureName(cameraTex)
        bgProgram = program(BG_VERTEX, BG_FRAGMENT)
        flatProgram = program(FLAT_VERTEX, FLAT_FRAGMENT)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        session.setDisplayGeometry(rotation(), w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = runCatching { session.update() }.getOrNull() ?: return
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); quadUv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, quadUv)
        }
        if (frame.timestamp == 0L) return
        drawBackground()
        val camera = frame.camera
        val pose = camera.pose
        // Heading of the camera's view direction on the floor, clockwise from the map's up (-z).
        val fwd = pose.zAxis
        val heading = kotlin.math.atan2(-fwd[0], fwd[2])
        if (camera.trackingState == TrackingState.TRACKING) {
            // Floor: the first horizontal plane or depth point straight below the phone; else 1.3 m under it.
            val hit = runCatching {
                frame.hitTest(floatArrayOf(pose.tx(), pose.ty(), pose.tz()), 0, floatArrayOf(0f, -1f, 0f), 0)
                    .firstOrNull { h ->
                        val t = h.trackable
                        (t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(h.hitPose)) || t is DepthPoint
                    }
            }.getOrNull()
            val y = hit?.hitPose?.ty() ?: (pose.ty() - 1.3f)
            floorY = if (floorY.isNaN()) y else floorY + 0.1f * (y - floorY)
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            drawTiles()
            drawMarker(pose.tx(), floorY + 0.01f, pose.tz(), heading)
        }
        val now = System.currentTimeMillis()
        if (now - lastReport >= 100) {
            lastReport = now
            val p = ArPose(
                pose.tx(), pose.tz(), floorY, heading, camera.trackingState, camera.trackingFailureReason,
                session.config.depthMode != Config.DepthMode.DISABLED,
            )
            main.post { onPose(p) }
        }
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "u_Dim"), DIM)
        val pos = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        val uv = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
        quad.position(0); quadUv.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, quadUv)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
        GLES20.glDepthMask(true)
    }

    /** Draws triangles from interleaved x, y, z, r, g, b, a vertices. */
    private fun drawFlat(buf: FloatBuffer, count: Int, mode: Int = GLES20.GL_TRIANGLES) {
        if (count == 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(flatProgram)
        val pos = GLES20.glGetAttribLocation(flatProgram, "a_Position")
        val col = GLES20.glGetAttribLocation(flatProgram, "a_Color")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatProgram, "u_Mvp"), 1, false, vp, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 28, buf)
        buf.position(3)
        GLES20.glVertexAttribPointer(col, 4, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(col)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(col)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawTiles() {
        val src = tiles
        if (src !== tileSource) {
            tileSource = src
            tileBuffer = floats(*src)
        }
        drawFlat(tileBuffer, src.size / 7)
    }

    /** WiFiman's position marker: a white-ringed blue dot with an arrow ahead of it, flat on the floor. */
    private fun drawMarker(x: Float, y: Float, z: Float, heading: Float) {
        val v = ArrayList<Float>()
        fun vertex(px: Float, pz: Float, c: FloatArray) { v += px; v += y; v += pz; v += c[0]; v += c[1]; v += c[2]; v += c[3] }
        fun disc(r: Float, c: FloatArray) {
            val n = 28
            for (i in 0 until n) {
                val a0 = 2 * Math.PI * i / n
                val a1 = 2 * Math.PI * (i + 1) / n
                vertex(x, z, c)
                vertex(x + r * cos(a0).toFloat(), z + r * sin(a0).toFloat(), c)
                vertex(x + r * cos(a1).toFloat(), z + r * sin(a1).toFloat(), c)
            }
        }
        val white = floatArrayOf(1f, 1f, 1f, 1f)
        val blue = floatArrayOf(0.11f, 0.42f, 0.95f, 1f)
        // Arrow: heading is clockwise from -z.
        val fx = sin(heading); val fz = -cos(heading)
        val sx = -fz; val sz = fx
        fun p(f: Float, s: Float) = (x + fx * f + sx * s) to (z + fz * f + sz * s)
        val (tx, tz) = p(0.2f, 0f)
        val (lx, lz) = p(0.1f, -0.06f)
        val (rx, rz) = p(0.1f, 0.06f)
        vertex(tx, tz, blue); vertex(lx, lz, blue); vertex(rx, rz, blue)
        disc(0.075f, white)
        disc(0.055f, blue)
        drawFlat(floats(*v.toFloatArray()), v.size / 7)
    }

    private companion object {
        /** Camera image brightness under the map. */
        const val DIM = 0.45f

        const val BG_VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        const val BG_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_Dim;
            void main() { gl_FragColor = vec4(texture2D(u_Texture, v_TexCoord).rgb * u_Dim, 1.0); }
        """
        const val FLAT_VERTEX = """
            attribute vec3 a_Position;
            attribute vec4 a_Color;
            uniform mat4 u_Mvp;
            varying vec4 v_Color;
            void main() { gl_Position = u_Mvp * vec4(a_Position, 1.0); v_Color = a_Color; }
        """
        const val FLAT_FRAGMENT = """
            precision mediump float;
            varying vec4 v_Color;
            void main() { gl_FragColor = v_Color; }
        """

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(maxOf(v.size, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }
    }
}
