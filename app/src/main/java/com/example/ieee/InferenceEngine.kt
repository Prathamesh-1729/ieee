package com.example.ieee

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections

class InferenceEngine(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // Load model from res/raw/beamformer_mobile.onnx
        val modelBytes = context.resources.openRawResource(R.raw.beamformer_mobile).readBytes()
        session = env.createSession(modelBytes)
    }

    /**
     * @param features Flat array [Freq * Time * 5]
     * @param leftSpec Original Left STFT
     * @param rightSpec Original Right STFT
     * @return Enhanced Audio (Time Domain)
     */
    fun runInference(
        features: FloatArray,
        leftSpec: Array<Array<Complex>>,
        rightSpec: Array<Array<Complex>>
    ): FloatArray {

        val numFrames = leftSpec.size
        val freqBins = 257

        // 1. Prepare Inputs for ONNX
        // ONNX expects: [Batch=1, Freq, Time, Channels]
        // But our Features are flattened F->T->C.
        // The wrapper export used 'features': {2: 'time'}.
        // The export wrapper expected [Batch, F, T, C].
        // Our FeatureExtractor flattened as F, then T, then C.
        // This memory layout corresponds exactly to [F, T, C].
        // We just need to wrap it in a FloatBuffer.

        val featBuffer = FloatBuffer.wrap(features)
        val featShape = longArrayOf(1, freqBins.toLong(), numFrames.toLong(), 5)

        // 2. Prepare Mix Real/Imag Inputs
        // We need to flatten Left/Right STFT into [1, F, T, 2]
        // Order: F -> T -> Mic(L/R)
        val sizeStft = freqBins * numFrames * 2
        val realBuf = FloatBuffer.allocate(sizeStft)
        val imagBuf = FloatBuffer.allocate(sizeStft)

        for (f in 0 until freqBins) {
            for (t in 0 until numFrames) {
                // Mic Left
                realBuf.put(leftSpec[t][f].r)
                imagBuf.put(leftSpec[t][f].i)
                // Mic Right
                realBuf.put(rightSpec[t][f].r)
                imagBuf.put(rightSpec[t][f].i)
            }
        }
        realBuf.rewind()
        imagBuf.rewind()

        val stftShape = longArrayOf(1, freqBins.toLong(), numFrames.toLong(), 2)

        // 3. Create Tensors
        val tFeats = OnnxTensor.createTensor(env, featBuffer, featShape)
        val tReal = OnnxTensor.createTensor(env, realBuf, stftShape)
        val tImag = OnnxTensor.createTensor(env, imagBuf, stftShape)

        // 4. Run Inference
        val inputs = mapOf(
            "feats" to tFeats,
            "mix_real" to tReal,
            "mix_imag" to tImag
        )

        // Outputs: "enh_real", "enh_imag" -> [1, F, T]
        val results = session.run(inputs)

        val outRealTensor = results[0] as OnnxTensor
        val outImagTensor = results[1] as OnnxTensor

        val outRealFlat = outRealTensor.floatBuffer
        val outImagFlat = outImagTensor.floatBuffer

        // 5. Reconstruct Spectrogram for Inverse STFT
        // ONNX Output is [1, F, T]. We need [T][F] for STFT.inverse
        val enhSpec = Array(numFrames) { Array(freqBins) { Complex(0f, 0f) } }

        // The float buffer is flat [F, T].
        // We need to read it carefully.
        for (f in 0 until freqBins) {
            for (t in 0 until numFrames) {
                // Calculate index in flat buffer
                // Buffer is F-major (F, T) because PyTorch defaults to row-major
                // but we flattened it F->T in input, output usually preserves order roughly.
                // However, PyTorch tensors are [Batch, F, T]. Flat buffer is F * T.
                // So Index = f * T + t
                val idx = f * numFrames + t

                val r = outRealFlat.get(idx)
                val i = outImagFlat.get(idx)

                enhSpec[t][f] = Complex(r, i)
            }
        }

        // 6. Inverse STFT
        val istft = STFT()
        val enhancedAudio = istft.inverse(enhSpec)

        // Close tensors
        tFeats.close(); tReal.close(); tImag.close(); results.close()

        return enhancedAudio
    }
}