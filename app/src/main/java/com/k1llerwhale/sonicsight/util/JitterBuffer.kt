package com.k1llerwhale.sonicsight.util

import android.media.AudioTrack
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Adaptive jitter buffer for smooth audio playback over unreliable networks.
 *
 * Instead of writing PCM bytes directly to [AudioTrack] as they arrive from gRPC
 * (which maps network jitter 1:1 to audible artifacts), this buffer:
 *
 * 1. **Accumulates** incoming PCM chunks in a ring buffer.
 * 2. **Delays playback start** until [initialBufferMs] of audio has accumulated.
 * 3. **Drains** audio to [AudioTrack] on a dedicated thread at steady real-time rate.
 * 4. **Inserts silence** when the buffer runs dry, preventing AudioTrack underrun clicks.
 * 5. **Adapts** the initial buffer target upward when underruns are detected.
 *
 * @param sink          The PCM sink to feed (production: [AudioTrackSink]). Must be ready; this class calls play().
 * @param sampleRate    Audio sample rate in Hz (11025).
 * @param initialBufferMs  Minimum ms of audio to buffer before starting playback (default 200ms).
 * @param maxBufferMs   Maximum ms of audio the ring buffer can hold (default 1500ms).
 */
class JitterBuffer(
    private val sink: PcmSink,
    private val sampleRate: Int = 11025,
    private var initialBufferMs: Int = 200,
    private val maxBufferMs: Int = 1500
) {

    /** Production constructor — unchanged signature (test seam S1, docs/SEAMS.md). */
    constructor(
        audioTrack: AudioTrack,
        sampleRate: Int = 11025,
        initialBufferMs: Int = 200,
        maxBufferMs: Int = 1500,
    ) : this(AudioTrackSink(audioTrack), sampleRate, initialBufferMs, maxBufferMs)
    companion object {
        private const val TAG = "JitterBuffer"
        // PCM16 mono: 2 bytes per sample
        private const val BYTES_PER_SAMPLE = 2
        // Maximum initial buffer target we'll adapt up to (ms)
        private const val MAX_INITIAL_BUFFER_MS = 500
        // How much to increase the initial buffer on repeated underruns (ms)
        private const val ADAPT_STEP_MS = 50
        // Number of underruns before adapting upward
        private const val ADAPT_UNDERRUN_THRESHOLD = 3
    }

    // Ring buffer sized for maxBufferMs
    private val maxBytes = (maxBufferMs * sampleRate * BYTES_PER_SAMPLE) / 1000
    private val ringBuffer = ByteArray(maxBytes)
    private var writePos = 0
    private var readPos = 0
    private var availableBytes = 0

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()

    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var playbackStarted = AtomicBoolean(false)

    // Adaptive metrics
    private var underrunCount = 0
    private var totalDrainCycles = 0L

    /** Observed underruns since start(). Test seam S1: read-only observer. */
    @get:VisibleForTesting
    internal val underruns: Int get() = underrunCount

    /** Current (possibly adapted) prebuffer target. Test seam S1: read-only observer. */
    @get:VisibleForTesting
    internal val effectiveInitialBufferMs: Int get() = initialBufferMs

    // The drain write size: how many bytes to write per AudioTrack.write() call.
    // We use a small chunk (~23ms = 256 samples = 512 bytes) for responsive draining.
    private val drainChunkSamples = 256
    private val drainChunkBytes = drainChunkSamples * BYTES_PER_SAMPLE

    // Silence chunk pre-allocated for underrun insertion
    private val silenceChunk = ByteArray(drainChunkBytes)

    /**
     * Start the drain thread. Call after AudioTrack is initialized.
     */
    fun start() {
        if (running.getAndSet(true)) return

        playbackStarted.set(false)
        underrunCount = 0
        totalDrainCycles = 0

        drainThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "JitterBuffer-Drain").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "Started: initialBuffer=${initialBufferMs}ms, maxBuffer=${maxBufferMs}ms")
    }

    /**
     * Stop the drain thread and reset buffer state.
     */
    fun stop() {
        if (!running.getAndSet(false)) return

        // Wake up the drain thread if it's waiting
        lock.withLock { dataAvailable.signalAll() }

        drainThread?.join(500)
        drainThread = null

        // Reset buffer state
        lock.withLock {
            writePos = 0
            readPos = 0
            availableBytes = 0
        }

        playbackStarted.set(false)
        Log.i(TAG, "Stopped. Underruns=$underrunCount, DrainCycles=$totalDrainCycles")
    }

    /**
     * Enqueue PCM16 bytes into the jitter buffer.
     * Non-blocking — if the buffer is full, the oldest data is overwritten.
     */
    fun write(pcmBytes: ByteArray) {
        if (!running.get() || pcmBytes.isEmpty()) return

        lock.withLock {
            val bytesToWrite = pcmBytes.size

            // If incoming data would overflow, drop oldest data (advance readPos)
            if (bytesToWrite > maxBytes) {
                // Chunk larger than entire buffer — only keep the tail
                val offset = bytesToWrite - maxBytes
                System.arraycopy(pcmBytes, offset, ringBuffer, 0, maxBytes)
                writePos = 0
                readPos = 0
                availableBytes = maxBytes
                dataAvailable.signalAll()
                return
            }

            // Check if we need to drop old data to make room
            val freeSpace = maxBytes - availableBytes
            if (bytesToWrite > freeSpace) {
                val overflow = bytesToWrite - freeSpace
                readPos = (readPos + overflow) % maxBytes
                availableBytes -= overflow
            }

            // Copy into ring buffer (may wrap around)
            val firstPart = minOf(bytesToWrite, maxBytes - writePos)
            System.arraycopy(pcmBytes, 0, ringBuffer, writePos, firstPart)
            if (firstPart < bytesToWrite) {
                System.arraycopy(pcmBytes, firstPart, ringBuffer, 0, bytesToWrite - firstPart)
            }
            writePos = (writePos + bytesToWrite) % maxBytes
            availableBytes += bytesToWrite

            dataAvailable.signalAll()
        }
    }

    /**
     * Current buffer fill level in milliseconds.
     */
    fun bufferLevelMs(): Int {
        lock.withLock {
            return (availableBytes * 1000) / (sampleRate * BYTES_PER_SAMPLE)
        }
    }

    // ── Internal drain loop ──

    private fun drainLoop() {
        val initialBufferBytes = (initialBufferMs * sampleRate * BYTES_PER_SAMPLE) / 1000
        val tempBuf = ByteArray(drainChunkBytes)
        var consecutiveUnderruns = 0

        // Phase 1: Wait for initial buffer to fill
        lock.withLock {
            while (running.get() && availableBytes < initialBufferBytes) {
                try {
                    dataAvailable.await()
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        if (!running.get()) return

        // Start playback
        try {
            if (sink.isReady) {
                sink.play()
                playbackStarted.set(true)
                Log.i(TAG, "Playback started after buffering ${bufferLevelMs()}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack: ${e.message}")
            return
        }

        // Phase 2: Steady drain
        while (running.get()) {
            totalDrainCycles++

            val bytesRead = lock.withLock {
                if (availableBytes >= drainChunkBytes) {
                    // Read a chunk from ring buffer
                    val firstPart = minOf(drainChunkBytes, maxBytes - readPos)
                    System.arraycopy(ringBuffer, readPos, tempBuf, 0, firstPart)
                    if (firstPart < drainChunkBytes) {
                        System.arraycopy(ringBuffer, 0, tempBuf, firstPart, drainChunkBytes - firstPart)
                    }
                    readPos = (readPos + drainChunkBytes) % maxBytes
                    availableBytes -= drainChunkBytes
                    consecutiveUnderruns = 0
                    drainChunkBytes
                } else if (availableBytes > 0) {
                    // Partial read — drain whatever is available
                    val available = availableBytes
                    val firstPart = minOf(available, maxBytes - readPos)
                    System.arraycopy(ringBuffer, readPos, tempBuf, 0, firstPart)
                    if (firstPart < available) {
                        System.arraycopy(ringBuffer, 0, tempBuf, firstPart, available - firstPart)
                    }
                    // Zero-fill the rest so we don't play stale data
                    for (i in available until drainChunkBytes) tempBuf[i] = 0
                    readPos = (readPos + available) % maxBytes
                    availableBytes = 0
                    available
                } else {
                    // Buffer empty — underrun
                    0
                }
            }

            if (bytesRead > 0) {
                // Write real audio to AudioTrack
                writeToTrack(tempBuf, drainChunkBytes)
            } else {
                // UNDERRUN: Insert silence to keep AudioTrack's internal clock running
                // This produces a brief silence gap (much better than clicks/pops)
                underrunCount++
                consecutiveUnderruns++
                writeToTrack(silenceChunk, drainChunkBytes)

                if (consecutiveUnderruns % ADAPT_UNDERRUN_THRESHOLD == 0 && 
                    initialBufferMs < MAX_INITIAL_BUFFER_MS) {
                    // Adapt: increase initial buffer for future streams
                    initialBufferMs = minOf(initialBufferMs + ADAPT_STEP_MS, MAX_INITIAL_BUFFER_MS)
                    Log.w(TAG, "Adapted initial buffer to ${initialBufferMs}ms (underruns=$underrunCount)")
                }

                if (consecutiveUnderruns == 1) {
                    Log.w(TAG, "Buffer underrun #$underrunCount — inserting silence")
                }
            }
        }
    }

    private fun writeToTrack(data: ByteArray, size: Int) {
        try {
            if (sink.isReady && sink.isPlaying) {
                // AudioTrack.write() blocks until the data is consumed, which naturally
                // paces the drain loop to real-time playback speed.
                sink.write(data, 0, size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack write error: ${e.message}")
        }
    }
}
