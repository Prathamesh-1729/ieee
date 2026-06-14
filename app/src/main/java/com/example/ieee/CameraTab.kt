package com.example.ieee

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// -----------------------------------------------------------
// 1. DATA MODELS
// -----------------------------------------------------------
data class ZoomLog(
    val timestampMs: Long,
    val zoomRatio: Float
)

data class RecordingSessionData(
    val sessionId: Long,
    val zoomData: List<ZoomLog>
)

// -----------------------------------------------------------
// 2. MAIN COMPOSABLE
// -----------------------------------------------------------
@Composable
fun CameraTab() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            val camera = perms[Manifest.permission.CAMERA] ?: false
            val audio = perms[Manifest.permission.RECORD_AUDIO] ?: false
            hasPermission = camera && audio
        }
    )

    // Check Permissions immediately
    LaunchedEffect(Unit) {
        val permCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val permAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)

        if (permCamera == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            permAudio == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    if (hasPermission) {
        // Show the Live Camera UI
        CameraRecordingScreen()
    } else {
        // Show Fallback "Grant Permission" Button
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }) {
                Text("Tap to Grant Camera Permissions")
            }
        }
    }
}

// -----------------------------------------------------------
// 3. CAMERA LOGIC & UI
// -----------------------------------------------------------
@Composable
fun CameraRecordingScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // State
    var isRecording by remember { mutableStateOf(false) }
    var recordingStatus by remember { mutableStateOf("Ready") }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    // CameraX Objects
    val previewView = remember { PreviewView(context) }
    var videoCapture: VideoCapture<Recorder>? by remember { mutableStateOf(null) }
    var activeRecording: Recording? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }

    // Helpers
    val stereoRecorder = remember { StereoRecorder(context) }
    val zoomLogs = remember { mutableStateListOf<ZoomLog>() }

    // --- A. Initialize Camera ---
    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // Use safe Quality Selector (Highest available, fallback to SD if needed)
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HIGHEST,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, videoCapture
            )

            // Listen to Zoom changes from Camera
            camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
                currentZoom = state.zoomRatio
                maxZoom = state.maxZoomRatio
            }
        } catch (e: Exception) {
            Log.e("CameraTab", "Camera Bind Failed", e)
            recordingStatus = "Camera Error: ${e.message}"
        }
    }

    // --- B. Pinch-to-Zoom Gesture ---
    val scaleGestureDetector = remember {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(current * detector.scaleFactor)
                return true
            }
        })
    }

    // --- C. UI Layout ---
    Box(modifier = Modifier.fillMaxSize()) {

        // 1. Full Screen Camera Preview
        AndroidView(
            factory = {
                previewView.apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    setOnTouchListener { _, e ->
                        scaleGestureDetector.onTouchEvent(e)
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Status Bar
        if (recordingStatus.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = recordingStatus,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // 3. Right Side Zoom Slider
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .height(250.dp)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Zoom", color = Color.White, fontSize = 12.sp)
            Slider(
                value = currentZoom,
                onValueChange = { camera?.cameraControl?.setZoomRatio(it) },
                valueRange = 1f..maxZoom,
                modifier = Modifier
                    .weight(1f)
                    .width(50.dp)
            )
            Text("${String.format("%.1f", currentZoom)}x", color = Color.White, fontSize = 12.sp)
        }

        // 4. Bottom Record Button
        Button(
            onClick = {
                if (isRecording) {
                    // STOP RECORDING
                    isRecording = false
                    recordingStatus = "Finalizing..."

                    // Stop Video
                    activeRecording?.stop()
                    activeRecording = null

                    // Stop Audio (Triggers Save)
                    stereoRecorder.stopRecording()

                } else {
                    // START RECORDING
                    isRecording = true
                    zoomLogs.clear()

                    val timestamp = System.currentTimeMillis()
                    val folderName = "ieee/$timestamp"

                    // --- Start Video (Silent) ---
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "video.mp4")
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/$folderName")
                    }

                    val outputOptions = MediaStoreOutputOptions.Builder(
                        context.contentResolver,
                        MediaStore.Files.getContentUri("external")
                    ).setContentValues(contentValues).build()

                    activeRecording = videoCapture?.output
                        ?.prepareRecording(context, outputOptions)
                        ?.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                            when(recordEvent) {
                                is VideoRecordEvent.Start -> {
                                    recordingStatus = "Recording... (0s)"

                                    // --- Start Audio ---
                                    scope.launch {
                                        stereoRecorder.startRecording(
                                            folderName = folderName,
                                            onFilesSaved = {
                                                // --- Save JSON Log ---
                                                saveJsonLogs(context, folderName, zoomLogs)
                                                recordingStatus = "Saved to Documents/$folderName"
                                                Toast.makeText(context, "All files saved!", Toast.LENGTH_LONG).show()
                                            },
                                            onError = { recordingStatus = "Audio Error: $it" }
                                        )
                                    }

                                    // --- Start Zoom Logger ---
                                    scope.launch {
                                        val startTime = System.currentTimeMillis()
                                        while (isRecording && isActive) {
                                            val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                                            zoomLogs.add(ZoomLog(System.currentTimeMillis() - startTime, z))

                                            // Update timer UI
                                            val sec = (System.currentTimeMillis() - startTime) / 1000
                                            recordingStatus = "Recording... (${sec}s)"

                                            delay(100) // Log 10 times per second
                                        }
                                    }
                                }
                                is VideoRecordEvent.Finalize -> {
                                    if (recordEvent.hasError()) {
                                        recordingStatus = "Video Error: ${recordEvent.cause?.message}"
                                    }
                                }
                            }
                        }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(80.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color.White else Color.Transparent
            ),
            border = androidx.compose.foundation.BorderStroke(4.dp, Color.White),
            contentPadding = PaddingValues(0.dp)
        ) {
            // Inner Red Circle Logic
            Box(
                modifier = Modifier
                    .size(if (isRecording) 30.dp else 60.dp) // Square if recording, Circle if not
                    .background(Color.Red, if (isRecording) RoundedCornerShape(4.dp) else CircleShape)
            )
        }
    }
}

// -----------------------------------------------------------
// 4. JSON HELPER
// -----------------------------------------------------------
fun saveJsonLogs(context: Context, folderName: String, logs: List<ZoomLog>) {
    val gson = Gson()
    val data = RecordingSessionData(System.currentTimeMillis(), logs)
    val jsonString = gson.toJson(data)

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "zoom_logs.json")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/$folderName")
    }

    try {
        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.write(jsonString.toByteArray())
            }
        }
    } catch (e: Exception) {
        Log.e("CameraTab", "JSON Save failed", e)
    }
}