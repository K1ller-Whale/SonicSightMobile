package com.k1llerwhale.sonicsight.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Anti-aliased integer-factor decimator for mono PCM16 audio.
 *
 * Purpose: Android only guarantees 44100 Hz on AudioRecord. Asking the
 * platform for 11025 Hz forces an OEM resampler that, on some devices,
 * silently destroys content above a few hundred Hz while leaving the PCM
 * structurally perfect. Capturing at 44100 and decimating here gives us a
 * known-good, linear-phase anti-aliasing filter instead.
 *
 * The filter is a Hamming-windowed sinc low-pass applied at the INPUT rate,
 * evaluated only at output positions, so cost scales with the output rate.
 * Filter state is carried across calls, so block boundaries introduce no
 * discontinuity and the output sample count stays exactly inputSamples/factor
 * on average regardless of how AudioRecord chunks its reads.
 *
 * Not thread safe: call process() from a single thread.
 */
class AudioDecimator(
    private val factor: Int = 4,
    numTaps: Int = 121,
    cutoffHz: Double = 5000.0,
    inputRateHz: Double = 44100.0
) {

    private val taps: FloatArray
    private val nTaps: Int
    private val history: FloatArray
    private var work: FloatArray = FloatArray(0)
    private var phase = 0

    init {
        require(factor >= 1)
        require(cutoffHz < inputRateHz / (2.0 * factor))

        // Force an odd tap count for exact linear phase.
        val n = if (numTaps % 2 == 0) numTaps + 1 else numTaps
        nTaps = n

        val fc = cutoffHz / inputRateHz
        val mid = (n - 1) / 2
        val raw = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val k = (i - mid).toDouble()
            val sinc = if (i == mid) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
            val v = sinc * window
            raw[i] = v
            sum += v
        }
        // Normalise to unity gain at DC so loudness is unchanged.
        taps = FloatArray(n) { (raw[it] / sum).toFloat() }
        history = FloatArray(n - 1)
    }

    // Maximum output bytes produced by an input of inputBytes bytes.
    fun maxOutputBytes(inputBytes: Int): Int = (inputBytes / 2) / factor * 2 + 8

    // Drop all filter state. Call before starting a new recording.
    fun reset() {
        java.util.Arrays.fill(history, 0f)
        phase = 0
    }

    // Filter and decimate lengthBytes of little-endian PCM16 from input into
    // output. Returns the number of bytes written to output.
    fun process(input: ByteArray, lengthBytes: Int, output: ByteArray): Int {
        val inSamples = lengthBytes / 2
        if (inSamples <= 0) return 0

        val hist = nTaps - 1
        val needed = hist + inSamples
        if (work.size < needed) work = FloatArray(needed)

        // work = carried history, then the new samples
        System.arraycopy(history, 0, work, 0, hist)
        var b = 0
        for (i in 0 until inSamples) {
            val lo = input[b].toInt() and 0xFF
            val hi = input[b + 1].toInt()
            work[hist + i] = ((hi shl 8) or lo).toFloat() / 32768f
            b += 2
        }

        var outBytes = 0
        for (i in 0 until inSamples) {
            phase++
            if (phase < factor) continue
            phase = 0

            var acc = 0f
            for (j in 0 until nTaps) acc += taps[j] * work[i + j]

            var s = (acc * 32768f).toInt()
            if (s > 32767) s = 32767
            if (s < -32768) s = -32768
            if (outBytes + 2 <= output.size) {
                output[outBytes] = (s and 0xFF).toByte()
                output[outBytes + 1] = ((s shr 8) and 0xFF).toByte()
                outBytes += 2
            }
        }

        // Carry the tail forward so the next block sees continuous history.
        System.arraycopy(work, needed - hist, history, 0, hist)
        return outBytes
    }
}
