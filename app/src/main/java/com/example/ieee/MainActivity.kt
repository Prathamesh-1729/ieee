package com.example.ieee

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Verify", "Inference", "Camera") // Added Camera

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = selectedTab == index,
                    onClick = { selectedTab = index }
                )
            }
        }

        when (selectedTab) {
            // 0 -> RecorderTab()
            0 -> VerifierTab()
            1 -> InferenceTab()
            2 -> CameraTab() // New Tab
        }
    }
}

// ------------------------------------------------------------------------
// TAB 1: RECORD (Your Existing Logic)
// ------------------------------------------------------------------------
@Composable
fun RecorderTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready to Record") }
    val recorder = remember { StereoRecorder(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                isRecording = true
                statusText = "Recording..."
                scope.launch {
                    // FIX: We now generate a folder name for audio-only recordings
                    val timestamp = System.currentTimeMillis()
                    val folderName = "ieee/audio_only_$timestamp"

                    recorder.startRecording(
                        folderName = folderName,
                        onFilesSaved = { files ->
                            statusText = "Saved to Documents/$folderName:\n${files.joinToString("\n")}"
                            isRecording = false
                            Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
                        },
                        onError = { error ->
                            statusText = "Error: $error"
                            isRecording = false
                        }
                    )
                }
            } else {
                Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
            }
        }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Stereo Recorder", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))
        Text(statusText, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            enabled = !isRecording,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Start Recording") }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                statusText = "Finalizing..."
                recorder.stopRecording()
            },
            enabled = isRecording,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Stop & Save") }
    }
}

// ------------------------------------------------------------------------
// TAB 2: VERIFY (Load WAV + Compute Features)
// ------------------------------------------------------------------------
@Composable
fun VerifierTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFileName by remember { mutableStateOf("No file selected") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultsText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    // File Picker Launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            selectedFileName = it.lastPathSegment ?: "Selected File"
            resultsText = "" // Clear previous results
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()), // Make it scrollable
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Feature Verification", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(24.dp))

        // 1. Select File Button
        Button(onClick = { launcher.launch("audio/x-wav") }) {
            Text("Select WAV File")
        }

        Text(selectedFileName, fontSize = 14.sp, modifier = Modifier.padding(8.dp))

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Process Button
        Button(
            enabled = selectedUri != null && !isProcessing,
            onClick = {
                isProcessing = true
                scope.launch {
                    val res = processAudioAndGetFeatures(context, selectedUri!!)
                    resultsText = res
                    isProcessing = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text(if (isProcessing) "Calculating..." else "Compute Features")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Results Display
        if (resultsText.isNotEmpty()) {
            Text("Result for FreqBin=10, Time=0:", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0))
            ) {
                Text(
                    text = resultsText,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

// --- Verification Logic Helper ---

suspend fun processAudioAndGetFeatures(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    try {
        // 1. Read Bytes from URI
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return@withContext "Error: Could not open file."
        val bytes = inputStream.readBytes()
        inputStream.close()

        // 2. Convert raw bytes to Left/Right FloatArrays
        // Assumes 16-bit PCM Stereo WAV (44 byte header)
        if (bytes.size < 44) return@withContext "Error: File too small"

        val dataSize = bytes.size - 44
        val shortCount = dataSize / 2
        val samplesPerChannel = shortCount / 2

        val left = FloatArray(samplesPerChannel)
        val right = FloatArray(samplesPerChannel)

        val buffer = ByteBuffer.wrap(bytes, 44, dataSize).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until samplesPerChannel) {
            if (!buffer.hasRemaining()) break
            val lVal = buffer.get()
            val rVal = buffer.get()

            // Normalize Short to Float (-1.0 to 1.0)
            left[i] = lVal / 32768f
            right[i] = rVal / 32768f
        }

        // 3. Run STFT
        val stft = STFT()
        val leftSpec = stft.perform(left)
        val rightSpec = stft.perform(right)

        if (leftSpec.isEmpty()) return@withContext "Error: Audio too short for STFT"

        // 4. Run Feature Extraction
        val extractor = FeatureExtractor()
        // Using Fixed Params: Center=90 deg, Width=20 deg
        val flattenedFeatures = extractor.computeFeatures(leftSpec, rightSpec, 90f, 20f)

        // 5. Extract Specific Indices (Matches Python Check)
        // [Batch, Freq, Time, Channels] flattened
        // Logic: F -> T -> C
        val numFrames = leftSpec.size
        val numChannels = 5
        val fIdx = 10 // Check Frequency Bin 10
        val tIdx = 0  // Check Time Frame 0

        val baseIndex = (fIdx * numFrames * numChannels) + (tIdx * numChannels)

        if (baseIndex + 4 >= flattenedFeatures.size) return@withContext "Error: Index out of bounds"

        val lps    = flattenedFeatures[baseIndex + 0]
        val cosIPD = flattenedFeatures[baseIndex + 1]
        val sinIPD = flattenedFeatures[baseIndex + 2]
        val dfIn   = flattenedFeatures[baseIndex + 3]
        val dfOut  = flattenedFeatures[baseIndex + 4]

        return@withContext """
            LPS:    %.6f
            CosIPD: %.6f
            SinIPD: %.6f
            DFin:   %.6f
            DFout:  %.6f
        """.trimIndent().format(lps, cosIPD, sinIPD, dfIn, dfOut)

    } catch (e: Exception) {
        return@withContext "Exception: ${e.message}"
    }
}

@Composable
fun InferenceTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFileName by remember { mutableStateOf("No file selected") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    // Parameters
    var angleStr by remember { mutableStateOf("90") }
    var widthStr by remember { mutableStateOf("20") }

    var statsText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            selectedFileName = it.lastPathSegment ?: "Selected File"
            statsText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AI Beamforming", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        // 1. File Selection
        Button(onClick = { launcher.launch("audio/x-wav") }) {
            Text("Select WAV File")
        }
        Text(selectedFileName, fontSize = 14.sp, modifier = Modifier.padding(8.dp))

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Parameters
        OutlinedTextField(
            value = angleStr,
            onValueChange = { angleStr = it },
            label = { Text("Target Angle (0-180)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = widthStr,
            onValueChange = { widthStr = it },
            label = { Text("Beam Width (deg)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Run Inference Button
        Button(
            enabled = selectedUri != null && !isProcessing,
            onClick = {
                val angle = angleStr.toFloatOrNull() ?: 90f
                val width = widthStr.toFloatOrNull() ?: 20f

                isProcessing = true
                scope.launch {
                    val resultMsg = runPipeline(context, selectedUri!!, angle, width)
                    statsText = resultMsg
                    isProcessing = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            Text(if (isProcessing) "Running AI Model..." else "Run Inference")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Stats
        if (statsText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Text(
                    text = statsText,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// --- The Logic Helper ---
suspend fun runPipeline(context: Context, uri: Uri, targetAngle: Float, width: Float): String = withContext(Dispatchers.IO) {
    try {
        val startTime = System.currentTimeMillis()

        // 1. Read File & Parse WAV Header
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext "Error loading file"
        val bytes = inputStream.readBytes()
        inputStream.close()

        if (bytes.size < 44) return@withContext "File too short"

        // Parse Header to find Channels
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buffer.getShort(22).toInt() // Offset 22 is NumChannels
        val sampleRate = buffer.getInt(24)         // Offset 24 is SampleRate

        if (sampleRate != 16000) return@withContext "Error: File must be 16kHz (Found $sampleRate Hz)"

        // 2. Convert to Left/Right Float Arrays
        val dataSize = bytes.size - 44
        // Move buffer position to data start
        buffer.position(44)
        val shortBuffer = buffer.asShortBuffer()

        val numSamples = dataSize / 2 // Total samples (shorts) in file
        val numFrames = numSamples / channels // Frames (points in time)

        val left = FloatArray(numFrames)
        val right = FloatArray(numFrames)

        if (channels == 2) {
            // STEREO: De-interleave
            for (i in 0 until numFrames) {
                if (!shortBuffer.hasRemaining()) break
                left[i] = shortBuffer.get() / 32768f
                right[i] = shortBuffer.get() / 32768f
            }
        } else if (channels == 1) {
            // MONO: Duplicate to both channels (Dual-Mono)
            // This prevents "Chipmunk speedup" / half duration
            for (i in 0 until numFrames) {
                if (!shortBuffer.hasRemaining()) break
                val sample = shortBuffer.get() / 32768f
                left[i] = sample
                right[i] = sample
            }
        } else {
            return@withContext "Error: Only Mono or Stereo WAV supported"
        }

        val timeLoad = System.currentTimeMillis()

        // 3. Preprocessing
        val stft = STFT()
        val lSpec = stft.perform(left)
        val rSpec = stft.perform(right)

        val extractor = FeatureExtractor()
        val features = extractor.computeFeatures(lSpec, rSpec, targetAngle, width)

        val timePre = System.currentTimeMillis()

        // 4. Inference
        val engine = InferenceEngine(context)
        val enhancedAudio = engine.runInference(features, lSpec, rSpec)

        val timeInfer = System.currentTimeMillis()

        // 5. Save Output
        val outName = "enhanced_${System.currentTimeMillis()}.wav"
        saveEnhancedAudio(context, enhancedAudio, outName)

        val endTime = System.currentTimeMillis()

        return@withContext """
            ✔ Success!
            Saved: Music/$outName
            
            Input: ${if(channels==2) "Stereo" else "Mono"} (${numFrames/16000f}s)
            
            Load Time:   ${timeLoad - startTime} ms
            Pre-Process: ${timePre - timeLoad} ms
            Inference:   ${timeInfer - timePre} ms
            Post-Process:${endTime - timeInfer} ms
            ---------------------
            Total Time:  ${endTime - startTime} ms
        """.trimIndent()

    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext "Failed: ${e.message}"
    }
}
fun saveEnhancedAudio(context: Context, audio: FloatArray, filename: String) {
    // 1. FIND PEAK & NORMALIZE
    // Find the loudest sample in the entire array
    var maxVal = 0.0001f // Avoid division by zero
    for (sample in audio) {
        val abs = Math.abs(sample)
        if (abs > maxVal) maxVal = abs
    }

    // Calculate how much to boost (Target volume is 0.9, slightly below max 1.0)
    val scaleFactor = 0.9f / maxVal

    // Debug Log: Check how much we are boosting
    // If scaleFactor is Huge (e.g., 1000.0), your raw audio was indeed silent.
    println("DEBUG: Boosting audio by factor of $scaleFactor")

    // Apply boost to every sample
    // We create a new array or modify in place
    val normalizedAudio = FloatArray(audio.size)
    for (i in audio.indices) {
        normalizedAudio[i] = audio[i] * scaleFactor
    }

    // 2. SAVE AS WAV (Standard Code)
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
    }

    val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return

    resolver.openOutputStream(uri)?.use { outputStream ->
        // Convert Float back to Short
        val shorts = ShortArray(normalizedAudio.size)
        for (i in normalizedAudio.indices) {
            // Now that it's normalized, this will produce audible 16-bit numbers
            val s = (normalizedAudio[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            shorts[i] = s
        }

        val byteBuffer = ByteBuffer.allocate(shorts.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(shorts)

        writeMonoWavHeader(outputStream, 16000, 16, byteBuffer.array().size)
        outputStream.write(byteBuffer.array())
    }
}

fun writeMonoWavHeader(out: OutputStream, sampleRate: Int, bitDepth: Int, totalDataLen: Int) {
    val channels = 1
    val byteRate = sampleRate * channels * bitDepth / 8
    val header = ByteBuffer.allocate(44).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        put("RIFF".toByteArray())
        putInt(totalDataLen + 36)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1.toShort())
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort((channels * bitDepth / 8).toShort())
        putShort(bitDepth.toShort())
        put("data".toByteArray())
        putInt(totalDataLen)
    }
    out.write(header.array())
}