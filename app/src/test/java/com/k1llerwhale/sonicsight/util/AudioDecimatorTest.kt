package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MU-1xx — audio decimation suite. Ids, gates and oracles:
 * mobile/mobile_test_targets.yaml. Golden vectors: independent NumPy oracle
 * (mobile/tools/gen_goldens.py), committed under src/test/resources/goldens.
 *
 * Deterministic: no clocks, no threads, no platform RNG — the only noise
 * source is the committed LCG below, replicated verbatim in the generator.
 */
class AudioDecimatorTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private fun toBytes(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[2 * i] = (samples[i] and 0xFF).toByte()
            out[2 * i + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun toSamples(bytes: ByteArray, lengthBytes: Int): IntArray {
        val out = IntArray(lengthBytes / 2)
        for (i in out.indices) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            out[i] = (hi shl 8) or lo
        }
        return out
    }

    /** Fresh decimator; feeds [samples] split into [chunkSizes]; returns all output samples. */
    private fun runChunked(
        factor: Int,
        cutoff: Double,
        samples: IntArray,
        chunkSizes: List<Int>,
    ): IntArray {
        require(chunkSizes.sum() == samples.size)
        val dec = AudioDecimator(factor, 121, cutoff, 44100.0)
        dec.reset()
        val all = ArrayList<Int>(samples.size / factor + 8)
        var pos = 0
        for (size in chunkSizes) {
            val input = toBytes(samples.copyOfRange(pos, pos + size))
            pos += size
            val output = ByteArray(dec.maxOutputBytes(input.size))
            val n = dec.process(input, input.size, output)
            toSamples(output, n).forEach { all.add(it) }
        }
        return all.toIntArray()
    }

    private fun runOneShot(factor: Int, cutoff: Double, samples: IntArray): IntArray =
        runChunked(factor, cutoff, samples, listOf(samples.size))

    private fun loadGolden(name: String): IntArray =
        javaClass.getResourceAsStream("/goldens/$name")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { it.trim().toInt() }.toList().toIntArray()
        }

    /** Committed LCG — byte-for-byte identical to mobile/tools/gen_goldens.py. */
    private fun lcgNoise(n: Int, seed: Long = 123456789L): IntArray {
        var x = seed
        val out = IntArray(n)
        for (i in 0 until n) {
            x = (1103515245L * x + 12345L) and 0x7FFFFFFFL
            out[i] = ((x % 65536L) - 32768L).toInt()
        }
        return out
    }

    private fun impulse(n: Int): IntArray = IntArray(n).also { it[0] = 32767 }

    private fun sine(freqHz: Double, amplitude: Int, n: Int): IntArray =
        IntArray(n) { i -> Math.round(amplitude * sin(2.0 * PI * freqHz * i / 44100.0)).toInt() }

    private fun maxAbsDiff(a: IntArray, b: IntArray): Int {
        assertEquals("length mismatch", a.size, b.size)
        var m = 0
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }

    private fun rms(x: IntArray, from: Int, to: Int): Double {
        var acc = 0.0
        for (i in from until to) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (to - from))
    }

    private val BLOCK = 5512          // locked constant: one 125 ms capture block
    private val NOISE_LEN = 3 * BLOCK

    // ── MU-101 · impulse/noise vs independent golden (tolerance: 2 LSB) ──

    @Test
    fun mu101_impulseResponseMatchesGolden_halvesProfile() {
        val out = runOneShot(4, 5000.0, impulse(BLOCK))
        assertTrue(maxAbsDiff(out, loadGolden("impulse_f4.csv")) <= 2)
    }

    @Test
    fun mu101_impulseResponseMatchesGolden_speechProfile() {
        val out = runOneShot(2, 10000.0, impulse(BLOCK))
        assertTrue(maxAbsDiff(out, loadGolden("impulse_f2.csv")) <= 2)
    }

    @Test
    fun mu101_noiseMatchesGolden_halvesProfile_blockwise() {
        // Blockwise feed: doubles as continuity evidence against a
        // whole-signal golden.
        val out = runChunked(4, 5000.0, lcgNoise(NOISE_LEN), listOf(BLOCK, BLOCK, BLOCK))
        assertTrue(maxAbsDiff(out, loadGolden("noise_f4.csv")) <= 2)
    }

    @Test
    fun mu101_noiseMatchesGolden_speechProfile_blockwise() {
        val out = runChunked(2, 10000.0, lcgNoise(NOISE_LEN), listOf(BLOCK, BLOCK, BLOCK))
        assertTrue(maxAbsDiff(out, loadGolden("noise_f2.csv")) <= 2)
    }

    // ── MU-102 · passband preservation (gate declared from first
    //    observation — see YAML MU-102) ─────────────────────────────────

    private fun passbandDeviationDb(factor: Int, cutoff: Double, freqHz: Double): Double {
        val n = 44100
        val out = runOneShot(factor, cutoff, sine(freqHz, 16384, n))
        val outRate = 44100 / factor
        // Skip filter settle (~121 input samples) generously: 200 output samples.
        val measured = rms(out, 200, out.size - 200)
        val expected = 16384.0 / sqrt(2.0)
        return 20.0 * log10(measured / expected)
    }

    @Test
    fun mu102_passbandTonePreserved_1kHz_halvesProfile() {
        val db = passbandDeviationDb(4, 5000.0, 1000.0)
        println("MU-102 observation: halves 1 kHz passband deviation = %.4f dB".format(db))
        assertTrue("passband deviation $db dB outside declared gate",
            abs(db) <= 0.1)
    }

    @Test
    fun mu102_passbandTonePreserved_2kHz_speechProfile() {
        val db = passbandDeviationDb(2, 10000.0, 2000.0)
        println("MU-102 observation: speech 2 kHz passband deviation = %.4f dB".format(db))
        assertTrue("passband deviation $db dB outside declared gate",
            abs(db) <= 0.1)
    }

    // ── MU-103 · stopband attenuation (gate declared from first
    //    observation — see YAML MU-103) ─────────────────────────────────

    private fun stopbandAttenuationDb(factor: Int, cutoff: Double, freqHz: Double): Double {
        val n = 44100
        val out = runOneShot(factor, cutoff, sine(freqHz, 16384, n))
        val measured = rms(out, 200, out.size - 200)
        val input = 16384.0 / sqrt(2.0)
        return 20.0 * log10(measured / input)
    }

    @Test
    fun mu103_stopbandToneAttenuated_7kHz_halvesProfile() {
        // Gate declared 2026-08-13 from first observation (-74.53 dB): -70 dB.
        val db = stopbandAttenuationDb(4, 5000.0, 7000.0)
        println("MU-103 observation: halves 7 kHz attenuation = %.2f dB".format(db))
        assertTrue("attenuation $db dB above declared -70 dB floor", db <= -70.0)
    }

    @Test
    fun mu103_stopbandToneAttenuated_12kHz_speechProfile() {
        // Gate declared 2026-08-13 from first observation (-80.45 dB): -75 dB.
        val db = stopbandAttenuationDb(2, 10000.0, 12000.0)
        println("MU-103 observation: speech 12 kHz attenuation = %.2f dB".format(db))
        assertTrue("attenuation $db dB above declared -75 dB floor", db <= -75.0)
    }

    // ── MU-104 · DC gain unity (derived gate: ±2 LSB) ───────────────────

    @Test
    fun mu104_dcGainUnity() {
        val out = runOneShot(4, 5000.0, IntArray(BLOCK) { 16384 })
        for (i in 200 until out.size) {
            assertTrue("DC plateau ${out[i]} at $i outside 16384±2", abs(out[i] - 16384) <= 2)
        }
    }

    // ── MU-105 · group delay 60 via multi-phase reconstruction ──────────

    private fun reconstructImpulseResponse(factor: Int, cutoff: Double): IntArray {
        // The decimated grid emits at input indices i ≡ factor-1 (mod factor),
        // which never lands on delay 60 for factor 4 — so reconstruct the full
        // 121-point response from `factor` zero-prefixed runs interleaved.
        val r = IntArray(121)
        val seen = BooleanArray(121)
        for (k in 0 until factor) {
            val input = IntArray(BLOCK)
            input[k] = 32767
            val out = runOneShot(factor, cutoff, input)
            for (n in out.indices) {
                val i = factor * n + (factor - 1)   // input index of output n
                val d = i - k                        // delay relative to the impulse
                if (d in 0..120) { r[d] = out[n]; seen[d] = true }
            }
        }
        assertTrue("reconstruction incomplete", seen.all { it })
        return r
    }

    @Test
    fun mu105_groupDelay60Samples_halvesProfile() {
        val r = reconstructImpulseResponse(4, 5000.0)
        val peak = r.indices.maxBy { r[it] }
        assertEquals("impulse-response peak not at delay 60", 60, peak)
        for (j in 1..60) {
            assertTrue("asymmetry at ±$j: ${r[60 - j]} vs ${r[60 + j]}",
                abs(r[60 - j] - r[60 + j]) <= 1)
        }
    }

    @Test
    fun mu105_groupDelay60Samples_speechProfile() {
        val r = reconstructImpulseResponse(2, 10000.0)
        val peak = r.indices.maxBy { r[it] }
        assertEquals("impulse-response peak not at delay 60", 60, peak)
        for (j in 1..60) {
            assertTrue("asymmetry at ±$j: ${r[60 - j]} vs ${r[60 + j]}",
                abs(r[60 - j] - r[60 + j]) <= 1)
        }
    }

    // ── MU-106 · state continuity: one-shot ≡ chunked, byte-identical ───

    @Test
    fun mu106_stateContinuity_regularBlocks() {
        val noise = lcgNoise(NOISE_LEN)
        val oneShot = runOneShot(4, 5000.0, noise)
        val chunked = runChunked(4, 5000.0, noise, listOf(BLOCK, BLOCK, BLOCK))
        assertArrayEquals(oneShot, chunked)
    }

    @Test
    fun mu106_stateContinuity_adversarialSplits() {
        val noise = lcgNoise(NOISE_LEN)
        val oneShot = runOneShot(4, 5000.0, noise)
        // Non-multiples of the factor, tiny and huge chunks mixed.
        val chunked = runChunked(4, 5000.0, noise, listOf(1, 3, 5508, 2000, 3512, 5512))
        assertArrayEquals(oneShot, chunked)
    }

    @Test
    fun mu106_stateContinuity_speechProfile() {
        val noise = lcgNoise(NOISE_LEN)
        val oneShot = runOneShot(2, 10000.0, noise)
        val chunked = runChunked(2, 10000.0, noise, listOf(7, 5505, 5512, 5512))
        assertArrayEquals(oneShot, chunked)
    }

    // ── MU-107/MU-108 · block arithmetic ────────────────────────────────

    @Test
    fun mu107_halvesBlockArithmetic_5512in_1378outPerBlock() {
        val dec = AudioDecimator(4, 121, 5000.0, 44100.0)
        dec.reset()
        val input = toBytes(lcgNoise(BLOCK))
        repeat(3) {
            val out = ByteArray(dec.maxOutputBytes(input.size))
            val n = dec.process(input, input.size, out)
            assertEquals(1378 * 2, n)
        }
    }

    @Test
    fun mu108_speechBlockArithmetic_5512in_2756outPerBlock() {
        val dec = AudioDecimator(2, 121, 10000.0, 44100.0)
        dec.reset()
        val input = toBytes(lcgNoise(BLOCK))
        repeat(3) {
            val out = ByteArray(dec.maxOutputBytes(input.size))
            val n = dec.process(input, input.size, out)
            assertEquals(2756 * 2, n)
        }
    }

    // ── MU-109 · zero-length input ──────────────────────────────────────

    @Test
    fun mu109_emptyInputProducesNothingAndPreservesState() {
        val noise = lcgNoise(BLOCK)
        val reference = runOneShot(4, 5000.0, noise)

        val dec = AudioDecimator(4, 121, 5000.0, 44100.0)
        dec.reset()
        val out = ByteArray(dec.maxOutputBytes(BLOCK * 2))
        assertEquals(0, dec.process(ByteArray(0), 0, out))
        assertEquals(0, dec.process(ByteArray(1), 1, out))   // < one sample
        val n = dec.process(toBytes(noise), BLOCK * 2, out)
        assertArrayEquals(reference, toSamples(out, n))
    }

    // ── MU-110 · remainder carry across non-multiple chunkings ──────────

    @Test
    fun mu110_remainderCarry_smallNonMultipleChunks() {
        val signal = lcgNoise(8)
        assertArrayEquals(
            runChunked(4, 5000.0, signal, listOf(8)),
            runChunked(4, 5000.0, signal, listOf(5, 3)),
        )
        assertArrayEquals(
            runChunked(4, 5000.0, signal, listOf(8)),
            runChunked(4, 5000.0, signal, listOf(1, 1, 1, 1, 1, 1, 1, 1)),
        )
    }

    // ── MU-111 · odd trailing byte silently discarded (characterization,
    //    candidate defect — see MOBILE_DEFECTS.md) ──────────────────────

    @Test
    fun mu111_oddTrailingByteDiscarded_characterization() {
        val four = lcgNoise(4)
        val evenBytes = toBytes(four)                       // 8 bytes
        val oddBytes = evenBytes.copyOf(9)                  // 9th byte, half a sample
        oddBytes[8] = 0x7F

        val decEven = AudioDecimator(4, 121, 5000.0, 44100.0).also { it.reset() }
        val decOdd = AudioDecimator(4, 121, 5000.0, 44100.0).also { it.reset() }
        val outEven = ByteArray(decEven.maxOutputBytes(8))
        val outOdd = ByteArray(decOdd.maxOutputBytes(9))
        val nEven = decEven.process(evenBytes, 8, outEven)
        val nOdd = decOdd.process(oddBytes, 9, outOdd)

        // Current behaviour: the trailing byte vanishes with no carry.
        assertEquals(nEven, nOdd)
        assertArrayEquals(toSamples(outEven, nEven), toSamples(outOdd, nOdd))
    }

    // ── MU-112 · undersized output buffer drops samples silently while
    //    state advances (characterization, candidate defect) ────────────

    @Test
    fun mu112_undersizedOutputBufferDropsSilently_characterization() {
        val noise = lcgNoise(2 * BLOCK)
        val block1 = noise.copyOfRange(0, BLOCK)
        val block2 = noise.copyOfRange(BLOCK, 2 * BLOCK)
        val reference = runChunked(4, 5000.0, noise, listOf(BLOCK, BLOCK))

        val dec = AudioDecimator(4, 121, 5000.0, 44100.0)
        dec.reset()
        // Room for exactly one output sample: the other 1377 are dropped
        // with no error signal.
        val tiny = ByteArray(2)
        assertEquals(2, dec.process(toBytes(block1), BLOCK * 2, tiny))

        // State (history + phase) advanced regardless: the next block's
        // output equals the uninterrupted run's second half exactly.
        val out = ByteArray(dec.maxOutputBytes(BLOCK * 2))
        val n = dec.process(toBytes(block2), BLOCK * 2, out)
        assertArrayEquals(
            reference.copyOfRange(1378, 2756),
            toSamples(out, n),
        )
    }

    // ── MU-113 · PCM16 little-endian wire layout ────────────────────────

    @Test
    fun mu113_outputBytesAreLittleEndian() {
        val dec = AudioDecimator(4, 121, 5000.0, 44100.0)
        dec.reset()
        val input = toBytes(lcgNoise(BLOCK))
        val out = ByteArray(dec.maxOutputBytes(input.size))
        val n = dec.process(input, input.size, out)
        val samples = toSamples(out, n)
        var sawNegative = false
        for (i in samples.indices) {
            // Low byte first on the wire; high byte carries the sign.
            assertEquals(out[2 * i], (samples[i] and 0xFF).toByte())
            assertEquals(out[2 * i + 1], ((samples[i] shr 8) and 0xFF).toByte())
            if (samples[i] < 0) sawNegative = true
        }
        assertTrue("test vector never exercised the sign byte", sawNegative)
    }

    @Test
    fun mu113_inputBytesDecodedAsLittleEndian() {
        // 0x0102 little-endian on the wire is (0x02, 0x01) = 258. A DC run of
        // it must settle near 258 (unity DC gain) — big-endian misread would
        // settle near 0x0201 = 513.
        val bytes = ByteArray(BLOCK * 2)
        for (i in 0 until BLOCK) { bytes[2 * i] = 0x02; bytes[2 * i + 1] = 0x01 }
        val dec = AudioDecimator(4, 121, 5000.0, 44100.0)
        dec.reset()
        val out = ByteArray(dec.maxOutputBytes(bytes.size))
        val n = dec.process(bytes, bytes.size, out)
        val samples = toSamples(out, n)
        for (i in 200 until samples.size) {
            assertTrue("plateau ${samples[i]} not near 258", abs(samples[i] - 258) <= 2)
        }
    }

    // ── MU-114 · sample/byte/duration conversions (locked constants) ────

    @Test
    fun mu114_sampleByteDurationConversions() {
        assertEquals(5512, 1378 * 4)               // halves capture block
        assertEquals(5512, 2756 * 2)               // speech capture block
        assertEquals(1378, 11025 / 8)              // samplesPerBlock, halves
        assertEquals(2756, 22050 / 8)              // samplesPerBlock, speech
        assertEquals(2756, 1378 * 2)               // halves block bytes
        assertEquals(5512, 2756 * 2)               // speech block bytes
        // All three block durations are the same 124988 µs (5512/44100 s;
        // the locked 125 ms figure is the 5512.5-sample truncation — stated).
        assertEquals(124988L, 1378L * 1_000_000L / 11025L)
        assertEquals(124988L, 2756L * 1_000_000L / 22050L)
        assertEquals(124988L, 5512L * 1_000_000L / 44100L)
    }

    // ── MU-115 · constructor invariants ─────────────────────────────────

    @Test
    fun mu115_factorBelowOneRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioDecimator(0, 121, 5000.0, 44100.0)
        }
    }

    @Test
    fun mu115_cutoffAtOrAboveNyquistRejected() {
        // Output Nyquist for /4 is 5512.5 Hz.
        assertThrows(IllegalArgumentException::class.java) {
            AudioDecimator(4, 121, 6000.0, 44100.0)
        }
    }

    @Test
    fun mu115_evenTapCountForcedOdd() {
        // 120 requested taps silently become 121 — outputs must be identical.
        val noise = lcgNoise(BLOCK)
        val out121 = runOneShot(4, 5000.0, noise)
        val dec120 = AudioDecimator(4, 120, 5000.0, 44100.0)
        dec120.reset()
        val out = ByteArray(dec120.maxOutputBytes(BLOCK * 2))
        val n = dec120.process(toBytes(noise), BLOCK * 2, out)
        assertArrayEquals(out121, toSamples(out, n))
    }
}
