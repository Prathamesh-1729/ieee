package com.example.ieee

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.cos
import kotlin.math.PI

data class Complex(val r: Float, val i: Float) {
    fun magSq(): Float = r * r + i * i
}

class STFT {
    private val nFft = 512
    private val hop = 256
    private val window: FloatArray
    private val fft = FloatFFT_1D(nFft.toLong())

    // Public so FeatureExtractor can access it
    val windowSum: Float

    init {
        window = FloatArray(nFft) { i ->
            (0.5 * (1 - cos(2.0 * PI * i / nFft))).toFloat()
        }
        windowSum = window.sum()
    }

    // --- FORWARD (Raw Energy) ---
    fun perform(samples: FloatArray): Array<Array<Complex>> {
        if (samples.size < nFft) return emptyArray()
        val numFrames = (samples.size - nFft) / hop + 1
        val spectrogram = Array(numFrames) { Array(nFft / 2 + 1) { Complex(0f, 0f) } }
        val fftBuffer = FloatArray(nFft * 2)

        for (t in 0 until numFrames) {
            val start = t * hop
            fftBuffer.fill(0f)
            for (i in 0 until nFft) {
                if (start + i < samples.size) {
                    fftBuffer[i * 2] = samples[start + i] * window[i]
                }
            }

            fft.complexForward(fftBuffer)

            for (f in 0 until (nFft / 2 + 1)) {
                // NO DIVISION HERE! Keep it raw.
                val real = fftBuffer[f * 2]
                val imag = fftBuffer[f * 2 + 1]
                spectrogram[t][f] = Complex(real, imag)
            }
        }
        return spectrogram
    }

    // --- INVERSE (Standard OLA) ---
    fun inverse(spectrogram: Array<Array<Complex>>): FloatArray {
        val numFrames = spectrogram.size
        if (numFrames == 0) return FloatArray(0)
        val outputLen = (numFrames - 1) * hop + nFft
        val output = FloatArray(outputLen)
        val fftBuffer = FloatArray(nFft * 2)

        for (t in 0 until numFrames) {
            fftBuffer.fill(0f)
            for (f in 0 until (nFft / 2 + 1)) {
                val c = spectrogram[t][f]
                // Raw Inverse
                fftBuffer[f * 2] = c.r
                fftBuffer[f * 2 + 1] = c.i

                if (f > 0 && f < nFft / 2) {
                    val revIdx = nFft - f
                    fftBuffer[revIdx * 2] = c.r
                    fftBuffer[revIdx * 2 + 1] = -c.i
                }
            }

            // JTransforms inverse with scaling (1/N)
            fft.complexInverse(fftBuffer, true)

            val startOffset = t * hop
            for (i in 0 until nFft) {
                if (startOffset + i < outputLen) {
                    // Standard Weighted OLA
                    // We divide by windowSum/2 roughly to normalize overlap gain
                    // But simpler is usually just adding and ensuring input wasn't clipped.
                    output[startOffset + i] += fftBuffer[i * 2] * window[i]
                }
            }
        }

        // Final Global Scaling (Empirical for Hann Window 50% Hop)
        // This ensures the output volume matches input volume
        val scalingFactor = 1.0f / (windowSum / 2.0f) // Approx 2/N
        for(i in output.indices) {
            output[i] *= scalingFactor
        }

        return output
    }
}