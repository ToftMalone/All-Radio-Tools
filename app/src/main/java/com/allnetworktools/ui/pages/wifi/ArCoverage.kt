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
import com.allnetworktools.data.CoverageMap
import com.allnetworktools.data.CoverageSample
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
 * Draws the camera image and, on the floor, a coloured disc at every measurement (green strong, red weak), plus a
 * pulsing one under the phone. Runs on the GL thread; [samples] is replaced from the UI thread.
 */
class ArCoverageRenderer(
    private val session: Session,
    private val rotation: () -> Int,
    private val colors: IntArray,
    private val onPose: (ArPose) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var samples: List<CoverageSample> = emptyList()
    @Volatile var currentRssi: Int = -70

    private val main = Handler(Looper.getMainLooper())
    private var cameraTex = 0
    private var bgProgram = 0
    private var discProgram = 0
    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadUv = floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val disc: FloatBuffer
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var floorY = Float.NaN
    private var lastReport = 0L
    private var width = 1
    private var height = 1

    init {
        // Triangle fan: centre, then the rim; the third coordinate is the distance from the centre (0 or 1).
        val n = 32
        val v = FloatArray((n + 2) * 3)
        for (i in 0..n) {
            val a = 2 * Math.PI * i / n
            v[(i + 1) * 3] = cos(a).toFloat()
            v[(i + 1) * 3 + 1] = sin(a).toFloat()
            v[(i + 1) * 3 + 2] = 1f
        }
        disc = floats(*v)
    }

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
        discProgram = program(DISC_VERTEX, DISC_FRAGMENT)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
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
            drawDiscs(pose.tx(), pose.tz())
        }
        val now = System.currentTimeMillis()
        if (now - lastReport >= 100) {
            lastReport = now
            // Heading of the camera's view direction on the floor, clockwise from the map's up (-z).
            val fwd = pose.zAxis
            val heading = kotlin.math.atan2(-fwd[0], fwd[2])
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

    private fun drawDiscs(camX: Float, camZ: Float) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(discProgram)
        val pos = GLES20.glGetAttribLocation(discProgram, "a_Position")
        val uMvp = GLES20.glGetUniformLocation(discProgram, "u_Mvp")
        val uColor = GLES20.glGetUniformLocation(discProgram, "u_Color")
        disc.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, disc)
        GLES20.glEnableVertexAttribArray(pos)
        fun draw(x: Float, y: Float, z: Float, r: Float, rssi: Float, alpha: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, z)
            // The mesh lies in x-y: turn it flat onto the floor.
            Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
            Matrix.scaleM(model, 0, r, r, 1f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            val c = color(CoverageMap.level(rssi))
            GLES20.glUniform4f(uColor, c[0], c[1], c[2], alpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 34)
        }
        val list = samples
        list.forEach { s -> draw(s.x, (if (s.floorY != 0f) s.floorY else floorY) + 0.005f, s.z, 0.24f, s.rssi.toFloat(), 0.8f) }
        val pulse = 0.28f + 0.06f * sin(System.nanoTime() / 2.5e8).toFloat()
        draw(camX, floorY + 0.01f, camZ, pulse, currentRssi.toFloat(), 0.55f)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Red → amber → green, as on the map. */
    private fun color(v: Float): FloatArray {
        val (a, b, t) = if (v < 0.5f) Triple(colors[0], colors[1], v * 2) else Triple(colors[1], colors[2], (v - 0.5f) * 2)
        fun ch(c: Int, s: Int) = ((c shr s) and 0xFF) / 255f
        return floatArrayOf(ch(a, 16) + (ch(b, 16) - ch(a, 16)) * t, ch(a, 8) + (ch(b, 8) - ch(a, 8)) * t, ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t)
    }

    private companion object {
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
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
        """
        const val DISC_VERTEX = """
            attribute vec3 a_Position;
            uniform mat4 u_Mvp;
            varying float v_R;
            void main() { gl_Position = u_Mvp * vec4(a_Position.xy, 0.0, 1.0); v_R = a_Position.z; }
        """
        const val DISC_FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            varying float v_R;
            void main() { gl_FragColor = vec4(u_Color.rgb, u_Color.a * (1.0 - 0.55 * v_R * v_R)); }
        """

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

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
