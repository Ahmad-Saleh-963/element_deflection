@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "DEPRECATION")

package com.example.task_image

import android.Manifest
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.task_image.ui.theme.Task_imageTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Task_imageTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    CameraPermission {
                        CameraScreen()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPermission(onGranted: @Composable () -> Unit) {
    val permissionState = rememberPermissionState(
        permission = Manifest.permission.CAMERA
    )

    LaunchedEffect(Unit) {
        permissionState.launchPermissionRequest()
    }

    if (permissionState.status.isGranted) {
        onGranted()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AddCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("يجب السماح بالكاميرا للبدء بالتتبع", color = Color.White)
                Button(onClick = { permissionState.launchPermissionRequest() }) {
                    Text("طلب الإذن")
                }
            }
        }
    }
}

@Composable
fun CameraScreen(
    viewModel: TrackingViewModel = viewModel()
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    // تحسين دقة التحليل عبر ضبط TargetResolution
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1080, 1920))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    imageAnalyzer.setAnalyzer(
                        Executors.newSingleThreadExecutor(),
                        ObjectAnalyzer(viewModel)
                    )

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        CrosshairOverlay(viewModel)
        TrackingOverlay(viewModel)
    }
}

@Composable
fun CrosshairOverlay(viewModel: TrackingViewModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val center = Offset(canvasWidth / 2, canvasHeight / 2)
        
        val color = if (viewModel.isCorrect) Color.Green else Color.White.copy(alpha = alpha)
        val strokeWidth = 1.dp.toPx()

        // شعيرات متعامدة دقيقة
        drawLine(
            color = color,
            start = Offset(center.x, 0f),
            end = Offset(center.x, canvasHeight),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = Offset(0f, center.y),
            end = Offset(canvasWidth, center.y),
            strokeWidth = strokeWidth
        )

        // دوائر تركيز احترافية
        drawCircle(
            color = color,
            radius = 40.dp.toPx(),
            center = center,
            style = Stroke(width = strokeWidth)
        )
        
        if (viewModel.isCorrect) {
            drawCircle(
                color = Color.Green.copy(alpha = 0.2f),
                radius = 45.dp.toPx(),
                center = center
            )
        }
    }
}

class ObjectAnalyzer(
    private val viewModel: TrackingViewModel
) : ImageAnalysis.Analyzer {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            detector.process(inputImage)
                .addOnSuccessListener { objects ->
                    if (objects.isNotEmpty()) {
                        val box = objects[0].boundingBox
                        viewModel.onObjectDetected(
                            box.centerX(),
                            box.centerY()
                        )
                    } else {
                        viewModel.onNoObjectDetected()
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}

class TrackingViewModel : ViewModel() {

    enum class State {
        SEARCHING,
        DETECTED,
        TRACKING,
        LOST
    }

    var currentState by mutableStateOf(State.SEARCHING)
        private set

    var statusMessage by mutableStateOf("جاري مسح المحيط...")
        private set

    var deviationX by mutableIntStateOf(0)
        private set
    var deviationY by mutableIntStateOf(0)
        private set

    var isCorrect by mutableStateOf(false)
        private set

    private var referencePoint: Offset? = null
    
    // متغيرات الفلتر المتقدم (Double Exponential Smoothing - Holt-Linear)
    private var level: Offset? = null
    private var trend: Offset = Offset(0f, 0f)
    private val alpha = 0.15f // معامل تنعيم الموقع
    private val beta = 0.1f   // معامل تنعيم الاتجاه

    fun onObjectDetected(objX: Int, objY: Int) {
        val rawPoint = Offset(objX.toFloat(), objY.toFloat())
        
        // تطبيق تنعيم هولت الخطي لتقليل الاهتزاز مع الحفاظ على سرعة الاستجابة
        if (level == null) {
            level = rawPoint
        } else {
            val lastLevel = level!!
            level = rawPoint * alpha + (lastLevel + trend) * (1 - alpha)
            trend = (level!! - lastLevel) * beta + trend * (1 - beta)
        }

        val smoothedPoint = level!!

        when (currentState) {
            State.SEARCHING, State.LOST -> {
                currentState = State.DETECTED
                statusMessage = "🎯 تم تحديد هدف"
            }
            State.TRACKING -> {
                updateTracking(smoothedPoint)
            }
            else -> {}
        }
    }

    fun onNoObjectDetected() {
        if (currentState == State.TRACKING) {
            currentState = State.LOST
            statusMessage = "⚠️ تنبيه: فقدان الاتصال بالهدف"
            isCorrect = false
        } else if (currentState == State.DETECTED) {
            currentState = State.SEARCHING
            statusMessage = "جاري مسح المحيط..."
        }
    }

    fun lockObject() {
        if (currentState == State.DETECTED) {
            referencePoint = level
            currentState = State.TRACKING
            statusMessage = "⛓️ تم تثبيت المرجع بنجاح"
        }
    }

    fun setReferenceToCurrent() {
        if (currentState == State.TRACKING) {
            referencePoint = level
            statusMessage = "📍 تم تحديث نقطة الصفر"
        }
    }

    fun reset() {
        currentState = State.SEARCHING
        referencePoint = null
        level = null
        trend = Offset(0f, 0f)
        deviationX = 0
        deviationY = 0
        isCorrect = false
        statusMessage = "جاري مسح المحيط..."
    }

    private fun updateTracking(current: Offset) {
        val ref = referencePoint ?: return
        
        val dx = current.x - ref.x
        val dy = current.y - ref.y
        
        deviationX = dx.toInt()
        deviationY = dy.toInt()
        
        // دقة فائقة (12 بكسل فقط)
        val threshold = 12f 
        isCorrect = abs(dx) < threshold && abs(dy) < threshold
    }
}

@Composable
fun TrackingOverlay(viewModel: TrackingViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        // شريط الحالة العلوي المطور
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(
                targetState = viewModel.currentState,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                }, label = "status_anim"
            ) { state ->
                val bgColor = when(state) {
                    TrackingViewModel.State.LOST -> Color.Red.copy(alpha = 0.8f)
                    TrackingViewModel.State.TRACKING -> if (viewModel.isCorrect) Color(0xFF00C853).copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
                    else -> Color.Black.copy(alpha = 0.6f)
                }
                
                Surface(
                    color = bgColor,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 8.dp
                ) {
                    Text(
                        text = viewModel.statusMessage,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))

            // لوحة البيانات الرقمية
            if (viewModel.currentState == TrackingViewModel.State.TRACKING || viewModel.currentState == TrackingViewModel.State.LOST) {
                DeviationPanel(viewModel)
            }
        }

        // أزرار التحكم السفلية الاحترافية
        ControlButtons(viewModel)
    }
}

@Composable
fun DeviationPanel(viewModel: TrackingViewModel) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .widthIn(min = 200.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (viewModel.isCorrect) "✅ المركز مالي" else "تحليل الانحراف (px)",
                color = if (viewModel.isCorrect) Color.Green else Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviationMetric(label = "X", value = viewModel.deviationX)
                Box(modifier = Modifier.width(1.dp).height(30.dp).background(Color.DarkGray).padding(horizontal = 16.dp))
                DeviationMetric(label = "Y", value = viewModel.deviationY)
            }
        }
    }
}

@Composable
fun DeviationMetric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(text = label, color = Color.Gray, fontSize = 10.sp)
        Text(
            text = "${if (value > 0) "+" else ""}$value",
            color = if (abs(value) < 12) Color.Green else Color.Yellow,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun ControlButtons(viewModel: TrackingViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 60.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // زر إعادة الضبط
            IconButton(
                onClick = { viewModel.reset() },
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.White)
            }

            // الزر الرئيسي التفاعلي
            AnimatedVisibility(
                visible = viewModel.currentState != TrackingViewModel.State.SEARCHING,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                val buttonColor = when(viewModel.currentState) {
                    TrackingViewModel.State.TRACKING -> Color(0xFF00C853)
                    else -> MaterialTheme.colorScheme.primary
                }
                
                Button(
                    onClick = {
                        if (viewModel.currentState == TrackingViewModel.State.DETECTED || viewModel.currentState == TrackingViewModel.State.LOST) {
                            viewModel.lockObject()
                        } else {
                            viewModel.setReferenceToCurrent()
                        }
                    },
                    modifier = Modifier
                        .height(56.dp)
                        .widthIn(min = 160.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    shape = RoundedCornerShape(28.dp),
                    elevation = ButtonDefaults.buttonElevation(8.dp)
                ) {
                    Icon(
                        if (viewModel.currentState == TrackingViewModel.State.TRACKING) Icons.Default.AddCircle else Icons.Default.Check,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (viewModel.currentState == TrackingViewModel.State.TRACKING) "تصفير المرجع" else "بدء التتبع الفائق",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
