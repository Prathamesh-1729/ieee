package com.example.ieee

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StereoRecorder(private val context: Context) {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    // Constants
    private val SAMPLE_RATE = 16000
    private val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
    private val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO

    // Modified to accept a specific folder name for this session
    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        folderName: String, // e.g. "ieee/1715009922"
        onFilesSaved: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        val bufferSize = maxOf(minBufferSize, 4096 * 4)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER) // Best for Stereo
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_MASK)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("Init failed: ${e.message}") }
            return@withContext
        }

        // Temp files in Cache (fast I/O)
        val rawMix = File(context.cacheDir, "temp_mix.pcm")
        val rawLeft = File(context.cacheDir, "temp_left.pcm")
        val rawRight = File(context.cacheDir, "temp_right.pcm")

        val outMix = FileOutputStream(rawMix)
        val outLeft = FileOutputStream(rawLeft)
        val outRight = FileOutputStream(rawRight)

        val buffer = FloatArray(bufferSize / 4)
        isRecording = true
        audioRecord?.startRecording()

        try {
            while (isRecording) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                if (readResult > 0) {
                    val count = readResult
                    val leftShorts = ShortArray(count / 2)
                    val rightShorts = ShortArray(count / 2)
                    val mixShorts = ShortArray(count)

                    for (i in 0 until count / 2) {
                        val leftVal = buffer[i * 2]
                        val rightVal = buffer[i * 2 + 1]

                        val lPcm = (leftVal.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                        val rPcm = (rightVal.coerceIn(-1f, 1f) * 32767).toInt().toShort()

                        leftShorts[i] = lPcm
                        rightShorts[i] = rPcm
                        mixShorts[i*2] = lPcm
                        mixShorts[i*2+1] = rPcm
                    }

                    outLeft.write(shortsToBytes(leftShorts))
                    outRight.write(shortsToBytes(rightShorts))
                    outMix.write(shortsToBytes(mixShorts))
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("Recording error: ${e.message}") }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
            outMix.close(); outLeft.close(); outRight.close()

            // Save to the specific folder "Documents/ieee/TIMESTAMP/"
            val savedNames = mutableListOf<String>()
            saveWav("mix.wav", rawMix, 2, folderName)?.let { savedNames.add(it) }
            saveWav("left.wav", rawLeft, 1, folderName)?.let { savedNames.add(it) }
            saveWav("right.wav", rawRight, 1, folderName)?.let { savedNames.add(it) }

            rawMix.delete(); rawLeft.delete(); rawRight.delete()

            withContext(Dispatchers.Main) {
                if (savedNames.isNotEmpty()) onFilesSaved(savedNames)
            }
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    private fun saveWav(filename: String, rawFile: File, channels: Int, folderName: String): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            // Crucial: Save to Documents/ieee/{timestamp}
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/$folderName")
        }

        // Use 'external' volume for generic file storage
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                val rawData = rawFile.readBytes()
                writeWavHeader(outputStream, channels, SAMPLE_RATE, 16, rawData.size)
                outputStream.write(rawData)
            }
            return filename
        } catch (e: Exception) {
            return null
        }
    }

    private fun writeWavHeader(out: OutputStream, channels: Int, sampleRate: Int, bitDepth: Int, totalLen: Int) {
        val byteRate = sampleRate * channels * bitDepth / 8
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(totalLen + 36)
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
            putInt(totalLen)
        }
        out.write(header.array())
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }
}