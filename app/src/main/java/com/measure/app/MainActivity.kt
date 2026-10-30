package com.measure.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.measure.app.databinding.ActivityMainBinding
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer, SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var session: ARCoreSessionHelper? = null

    private var startPose: Pose? = null
    private var currentPose: Pose? = null
    private var endPose: Pose? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        setupSensors()
        setupListeners()
        checkCameraPermission()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun setupListeners() {
        binding.fabAdd.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            currentPose?.let { hitPose ->
                if (startPose == null) {
                    startPose = hitPose
                    binding.distanceText.text = "Measuring..."
                } else if (endPose == null) {
                    endPose = hitPose
                    updateDistanceDisplay(startPose!!, endPose!!)
                } else {
                    resetMeasurement()
                    startPose = hitPose
                }
            } ?: run {
                Toast.makeText(this, "Finding surface...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabClear.setOnClickListener {
            resetMeasurement()
        }
    }

    private fun resetMeasurement() {
        startPose = null
        endPose = null
        binding.distanceText.text = getString(R.string.point_at_surface)
    }

    private fun updateDistanceDisplay(p1: Pose, p2: Pose) {
        val dx = p2.tx() - p1.tx()
        val dy = p2.ty() - p1.ty()
        val dz = p2.tz() - p1.tz()
        val distanceMeters = sqrt(dx * dx + dy * dy + dz * dz)
        val cm = distanceMeters * 100
        val inches = distanceMeters * 39.3701

        runOnUiThread {
            binding.distanceText.text = String.format(Locale.US, "%.1f cm (%.1f in)", cm, inches)
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (session == null) {
                try {
                    val arSession = Session(this)
                    val config = Config(arSession).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }
                    arSession.configure(config)
                    session = ARCoreSessionHelper(arSession)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                session?.arSession?.resume()
            } catch (e: CameraNotAvailableException) {
                e.printStackTrace()
            }
            binding.surfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        binding.surfaceView.onPause()
        session?.arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.arSession?.close()
        session = null
    }

    // GLSurfaceView.Renderer callbacks
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.arSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session?.arSession ?: return

        try {
            val frame = arSession.update()
            val camera = frame.camera

            if (camera.trackingState == TrackingState.TRACKING) {
                // Raycast center of screen
                val hits = frame.hitTest(binding.surfaceView.width / 2f, binding.surfaceView.height / 2f)
                val hit = hits.firstOrNull {
                    val trackable = it.trackable
                    trackable is Plane && trackable.isPoseInPolygon(it.hitPose)
                }

                hit?.let {
                    currentPose = it.hitPose
                    if (startPose != null && endPose == null) {
                        updateDistanceDisplay(startPose!!, currentPose!!)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // SensorEventListener (Bubble Level)
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val pitch = atan2(x.toDouble(), sqrt((y * y + z * z).toDouble())) * 180 / Math.PI
            val angle = abs(pitch.roundToInt())
            binding.levelText.text = "$angle°"
            if (angle == 0) {
                binding.levelText.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
            } else {
                binding.levelText.setBackgroundColor(ContextCompat.getColor(this, R.color.dark_gray))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

class ARCoreSessionHelper(val arSession: Session)
