package com.example.ieee

import kotlin.math.*

class FeatureExtractor {

    // --- Constants matching Python ---
    // Constants
    private val WINDOW_SUM = 256.0f // Sum of Hann 512
    private val FS = 16000f
    private val MIC_SPACING = 0.08 // 8 cm (Critical: Matches training)
    private val SOUND_SPEED = 343.0f
    private val NFFT = 512
    private val FREQ_BINS = NFFT / 2 + 1 // 257 bins

    // Angle Grid: 0, 10, 20 ... 360 (37 angles)
    private val ANGLE_STEP = 10
    private val ANGLES = (0..360 step ANGLE_STEP).toList()

    // Pre-computed table: expected phase shift for every Freq and Angle
    // Dimensions: [Freq][Angle]
    private val phiTable: Array<FloatArray> = precomputePhi()

    /**
     * Main function to convert Spectrograms to Model Input
     * * @param leftSpec: Output from STFT [Time][Freq]
     * @param rightSpec: Output from STFT [Time][Freq]
     * @param targetAngleDeg: Where you want to listen (e.g., 90.0 for center)
     * @param fovWidthDeg: How wide is the beam (e.g., 20.0)
     * * @return FloatArray flattened (Size: Time * Freq * 5) ready for ONNX
     */
    fun computeFeatures(
        leftSpec: Array<Array<Complex>>,
        rightSpec: Array<Array<Complex>>,
        targetAngleDeg: Float,
        fovWidthDeg: Float
    ): FloatArray {

        val numFrames = leftSpec.size
        // 5 Channels: LPS, CosIPD, SinIPD, DFin, DFout
        val numChannels = 5

        // Output buffer flattened: [Batch=1, Freq, Time, Channels]
        // Note: PyTorch order is usually (Batch, Channel, Freq, Time) or (Batch, Freq, Time, Channel).
        // Your Python script output `feats` as [F, T, 5].
        // ONNX Runtime usually expects a flat FloatBuffer.
        // We will flatten as: Time-major loop to match streaming, but logic below creates [F, T, 5] structure.

        // Size: Freq * Time * 5
        val outputSize = FREQ_BINS * numFrames * numChannels
        val features = FloatArray(outputSize)

        // Pre-calculate which angles are "Inside" the FOV
        val inMask = getFovMask(targetAngleDeg, fovWidthDeg)

        var ptr = 0

        // Important: Loop order must match exactly how Python np.stack works
        // Python: feats = stack([...], axis=-1) -> [F, T, 5]
        // So we iterate: Freq -> Time -> Channels

        for (f in 0 until FREQ_BINS) {
            for (t in 0 until numFrames) {
                val L = leftSpec[t][f]
                val R = rightSpec[t][f]

                // 1. LPS (Log Power Spectrum)
                // STFT is RAW now. We must Normalize before Log to match Python Scipy.
                // Scale = X / windowSum
                // Power = (X/S)^2 = X^2 / S^2

                val rawMagSq = L.magSq()
                val normMagSq = rawMagSq / (WINDOW_SUM * WINDOW_SUM)

                val lps = ln(normMagSq.coerceAtLeast(1e-8f))

                // 2. IPD & 3. DF
                // Phase is ratio-based (atan2), so Scaling cancels out.
                // No changes needed here!
                val crossRe = L.r * R.r + L.i * R.i
                val crossIm = L.i * R.r - L.r * R.i
                val angle = atan2(crossIm, crossRe)

                val cosIPD = cos(angle)
                val sinIPD = sin(angle)

                // 3. Directional Features (DF)
                // We compare the actual phase (angle) with expected phase (phiTable)
                // for ALL angles, then take the Max for In-FOV and Out-FOV.

                var maxIn = -1e9f
                var maxOut = -1e9f

                for (k in ANGLES.indices) {
                    // d_theta(f,t,k) = cos(IPD - phi(f,k))
                    // This is "Cosine Similarity" between measured phase and theoretical phase
                    val phi = phiTable[f][k]
                    val sim = cos(angle - phi)

                    if (inMask[k]) {
                        if (sim > maxIn) maxIn = sim
                    } else {
                        if (sim > maxOut) maxOut = sim
                    }
                }

                // 4. Fill Buffer (Order matches Python stack)
                features[ptr++] = lps
                features[ptr++] = cosIPD
                features[ptr++] = sinIPD
                features[ptr++] = maxIn
                features[ptr++] = maxOut
            }
        }

        return features
    }

    // --- Helpers ---

    /**
     * Pre-computes the expected phase difference for every frequency bin
     * for every angle in our grid (0..360).
     */
    private fun precomputePhi(): Array<FloatArray> {
        val table = Array(FREQ_BINS) { FloatArray(ANGLES.size) }

        // Array of frequencies in Hz
        val freqs = FloatArray(FREQ_BINS) { i -> i * FS / NFFT }

        for (k in ANGLES.indices) {
            val thetaDeg = ANGLES[k]
            val thetaRad = Math.toRadians(thetaDeg.toDouble()).toFloat()

            // Time delay tau = (d * cos(theta)) / c
            val tau = (MIC_SPACING * cos(thetaRad)) / SOUND_SPEED

            for (f in 0 until FREQ_BINS) {
                // Phase phi = 2 * pi * freq * tau
                table[f][k] = (2.0 * PI * freqs[f] * tau).toFloat()
            }
        }
        return table
    }

    /**
     * Determines which indices in our ANGLE grid fall inside the user's target FOV.
     */
    private fun getFovMask(target: Float, width: Float): BooleanArray {
        val mask = BooleanArray(ANGLES.size)
        val half = width / 2f
        val low = target - half
        val high = target + half

        var anyIn = false
        for (i in ANGLES.indices) {
            val a = ANGLES[i].toFloat()
            if (a in low..high) {
                mask[i] = true
                anyIn = true
            } else {
                mask[i] = false
            }
        }

        // Safety: If FOV is too narrow and misses all grid points, select nearest
        if (!anyIn) {
            var minDist = Float.MAX_VALUE
            var bestIdx = 0
            for (i in ANGLES.indices) {
                val dist = abs(ANGLES[i] - target)
                if (dist < minDist) {
                    minDist = dist
                    bestIdx = i
                }
            }
            mask[bestIdx] = true
        }

        return mask
    }
}