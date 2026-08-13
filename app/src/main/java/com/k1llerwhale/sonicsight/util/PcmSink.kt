package com.k1llerwhale.sonicsight.util

import android.media.AudioTrack

/**
 * Minimal sink abstraction over the parts of [AudioTrack] that
 * [JitterBuffer] touches. Test seam S1 (docs/SEAMS.md): lets the MU-2xx
 * suite drive the drain loop deterministically on the JVM with a fake,
 * blocking sink. Production behaviour is unchanged — [AudioTrackSink] is a
 * 1:1 pass-through and the [JitterBuffer] AudioTrack constructor still
 * exists with the same signature.
 */
interface PcmSink {
    /** AudioTrack.state == STATE_INITIALIZED */
    val isReady: Boolean

    /** AudioTrack.playState == PLAYSTATE_PLAYING */
    val isPlaying: Boolean

    fun play()

    /** Blocking write, AudioTrack.write semantics: paces the drain loop. */
    fun write(data: ByteArray, offset: Int, size: Int): Int
}

/** Production implementation: direct pass-through to [AudioTrack]. */
class AudioTrackSink(private val track: AudioTrack) : PcmSink {
    override val isReady: Boolean
        get() = track.state == AudioTrack.STATE_INITIALIZED

    override val isPlaying: Boolean
        get() = track.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun play() = track.play()

    override fun write(data: ByteArray, offset: Int, size: Int): Int =
        track.write(data, offset, size)
}
