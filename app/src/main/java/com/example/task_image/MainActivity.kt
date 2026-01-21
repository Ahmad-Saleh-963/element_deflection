package com.example.task_image

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.round

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // صلاحية الكاميرا
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                0
            )
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraCalibrationScreen()
            }
        }
    }

    // ==================== Kalman Filter Class ====================
    class KalmanFilter(var q: Float = 0.01f, var r: Float = 0.1f) {
        private var x = 0f // القيمة المقدرة
        private var p = 1f // الخطأ المقدر
        private var k = 0f // كلف الكالمان
        fun update(measurement: Float): Float {
            // التنبؤ
            p += q
            // التحديث
            k = p / (p + r)
            x += k * (measurement - x)
            p *= (1 - k)
            return x
        }
    }

    @Composable
    fun CameraCalibrationScreen() {
        val lifecycleOwner = LocalLifecycleOwner.current

        var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

        var yaw by remember { mutableStateOf(0f) }
        var pitch by remember { mutableStateOf(0f) }

        // Kalman Filters
        val kalmanYaw = remember { KalmanFilter(0.001f, 0.1f) }
        val kalmanPitch = remember { KalmanFilter(0.001f, 0.1f) }
        var smoothYaw by remember { mutableStateOf(0f) }
        var smoothPitch by remember { mutableStateOf(0f) }

        var zeroYaw by remember { mutableStateOf(0f) }
        var zeroPitch by remember { mutableStateOf(0f) }

        var calibrated by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)

                    yaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

                    smoothYaw = kalmanYaw.update(yaw)
                    smoothPitch = kalmanPitch.update(pitch)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(
                listener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            onDispose { sensorManager.unregisterListener(listener) }
        }

        val x = if (calibrated) normalize(smoothYaw - zeroYaw) else 0f
        val y = if (calibrated) normalize(-(smoothPitch - zeroPitch)) else 0f

        val STABLE = 0.2f
        val isStable = calibrated && abs(x) < STABLE && abs(y) < STABLE

        val indicatorColor = when {
            !calibrated -> Color.Gray
            isStable -> Color(0xFF4CAF50)
            else -> Color(0xFFE53935)
        }

        Box(modifier = Modifier.fillMaxSize()) {

            // 📷 Camera Preview
            AndroidView(
                factory = {
                    val previewView = PreviewView(it)
                    val providerFuture = ProcessCameraProvider.getInstance(it)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        val camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                        cameraControl = camera.cameraControl
                    }, ContextCompat.getMainExecutor(it))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // 🎯 شبكة 3x3 + مؤشر الحركة
            CameraGridOverlay(
                modifier = Modifier.fillMaxSize(),
                xOffset = x,
                yOffset = y,
                color = indicatorColor
            )

            // 🧾 واجهة التحكم
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    "موازنة الكاميرا",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                if (!calibrated) {
                    Text("اضغط (بدء) لتعيين الوضع المرجعي", color = Color.LightGray)
                } else {
                    Text(
                        "X أفقي: ${format(x)}°   |   Y عمودي: ${format(y)}°",
                        color = indicatorColor
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            zeroYaw = smoothYaw
                            zeroPitch = smoothPitch
                            calibrated = true
                        }
                    ) { Text("بدء") }

                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        onClick = { calibrated = false }
                    ) { Text("إعادة ضبط") }
                }
            }
        }
    }

    @Composable
    fun CameraGridOverlay(
        modifier: Modifier = Modifier,
        xOffset: Float,
        yOffset: Float,
        color: Color,
        showCenter: Boolean = true
    ) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height

            val thirdW = w / 3f
            val thirdH = h / 3f

            val lineColor = color.copy(alpha = 0.35f)
            val centerColor = color.copy(alpha = 0.85f)

            // 🔲 شبكة 3x3
            drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, h), strokeWidth = 1.5f)
            drawLine(lineColor, Offset(thirdW * 2, 0f), Offset(thirdW * 2, h), strokeWidth = 1.5f)
            drawLine(lineColor, Offset(0f, thirdH), Offset(w, thirdH), strokeWidth = 1.5f)
            drawLine(lineColor, Offset(0f, thirdH * 2), Offset(w, thirdH * 2), strokeWidth = 1.5f)

            if (showCenter) {
                val cx = w / 2
                val cy = h / 2

                val maxOffset = 90f
                val scale = 3.2f
                val dx = (xOffset * scale).coerceIn(-maxOffset, maxOffset)
                val dy = (yOffset * scale).coerceIn(-maxOffset, maxOffset)

                drawCircle(centerColor, radius = 18f, center = Offset(cx + dx, cy + dy))
                drawLine(centerColor, Offset(cx - 30, cy), Offset(cx + 30, cy), strokeWidth = 2.5f)
                drawLine(centerColor, Offset(cx, cy - 30), Offset(cx, cy + 30), strokeWidth = 2.5f)
                drawCircle(centerColor, radius = 50f, center = Offset(cx, cy), style = Stroke(2.5f))
            }
        }
    }

    private fun format(v: Float): String = (round(v * 100) / 100).toString()

    private fun normalize(a: Float): Float {
        var angle = a
        while (angle > 180f) angle -= 360f
        while (angle < -180f) angle += 360f
        return if (abs(angle) < 0.12f) 0f else angle
    }
}
