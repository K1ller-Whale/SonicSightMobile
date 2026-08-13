package com.k1llerwhale.sonicsight.grpc

import com.k1llerwhale.sonicsight.data.model.ModelProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * MU-8xx — wire contract (mobile/mobile_test_targets.yaml MU-801..804).
 *
 * MU-803/804 use HAND-DERIVED wire bytes as the oracle: every byte below is
 * constructed from the protobuf wire-format spec (tag = (field_number << 3)
 * | wire_type; varints LSB-first with continuation bit; floats fixed32
 * little-endian) and the field numbers in sonicsight.proto — independent of
 * the lite runtime under test, which lacks descriptor reflection entirely.
 */
class WireContractTest {

    // ── MU-801 · cross-repo proto identity (NFR-COMPAT-001) ─────────────

    /**
     * SHA-256 of the vendored sonicsight.proto, observed 2026-08-13 and
     * verified byte-identical to SonicSightBackend/sonicsight.proto on the
     * same checkout (Windows, current .gitattributes/autocrlf state).
     *
     * TO UPDATE DELIBERATELY: change the proto in BOTH repos in the same
     * change set, re-hash (`Get-FileHash -Algorithm SHA256 <file>`), paste
     * the new value here and in the backend's counterpart check. NEVER
     * update this constant just to make a red test green — a mismatch means
     * the two repos disagree about the wire contract.
     */
    private val PINNED_SHA256 =
        "ba76eaae8494a7d225be7b324ca53172c184958ea8e8be814984d3d9abb78005"

    @Test
    fun mu801_vendoredProtoMatchesPinnedSha256() {
        val proto = sequenceOf(
            File("src/main/proto/sonicsight.proto"),
            File("app/src/main/proto/sonicsight.proto"),
        ).firstOrNull { it.exists() } ?: error("sonicsight.proto not found from ${File(".").absolutePath}")

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(proto.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(PINNED_SHA256, digest)
    }

    // ── MU-802 · speech wire rate pin (REC-3) ───────────────────────────

    /**
     * 22050 Hz IS the speech wire rate. sonicsight.proto:199-204 mentions
     * 21000 Hz only as the server engine's INTERNAL rate ("the engine
     * converts its internal 21000 Hz back to the wire rate; the registry's
     * output_sample_rate is authoritative"). Do not "correct" this to 21000.
     */
    @Test
    fun mu802_speechWireRateIs22050() {
        assertEquals(22050, ModelProfile.MULTISENSORY.streamRate)
        assertEquals(ModelProfile.CAPTURE_RATE,
            ModelProfile.MULTISENSORY.streamRate * ModelProfile.MULTISENSORY.decimFactor)
    }

    /** Data basis of the audio-field selection rule (audio_pcm_hi iff hi rate). */
    @Test
    fun mu802_onlyMultisensoryIsAboveBaseRate() {
        assertTrue(ModelProfile.MULTISENSORY.streamRate > 11025)
        assertFalse(ModelProfile.SONICSIGHT.streamRate > 11025)
        assertFalse(ModelProfile.SONICSIGHT_PIXEL.streamRate > 11025)
    }

    // ── MU-803 · hand-built fixtures decode to expected fields ──────────

    @Test
    fun mu803_streamResultFixtureDecodes() {
        val fixture = byteArrayOf(
            0x08, 0x01,                                     // 1 success: true
            0x18, 0xE8.toByte(), 0x07,                      // 3 timestamp_ms: 1000
            0x22, 0x03, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), // 4 left_audio_pcm
            0x68, 0x05,                                     // 13 sequence_number: 5
            0x70, 0xE2.toByte(), 0x0A,                      // 14 audio_sample_count: 1378
            0x7A, 0x0C) + "multisensory".toByteArray() + byteArrayOf( // 15 model_id
            0x80.toByte(), 0x01, 0x01,                      // 16 heatmap_count: 1
            0x9A.toByte(), 0x01, 0x04, 0x10, 0x20, 0x30, 0x40, // 19 energy_map: 4 bytes
            0xA0.toByte(), 0x01, 0x0E,                      // 20 grid_width: 14
            0xA8.toByte(), 0x01, 0x0E,                      // 21 grid_height: 14
            0xC0.toByte(), 0x01, 0x07,                      // 24 window_id: 7
        )

        val r = StreamResult.parseFrom(fixture)
        assertTrue(r.success)
        assertEquals(1000L, r.timestampMs)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            r.leftAudioPcm.toByteArray())
        assertEquals(5, r.sequenceNumber)
        assertEquals(1378, r.audioSampleCount)
        assertEquals("multisensory", r.modelId)
        assertEquals(1, r.heatmapCount)
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40), r.energyMap.toByteArray())
        assertEquals(14, r.gridWidth)
        assertEquals(14, r.gridHeight)
        assertEquals(7L, r.windowId)
        // Untouched fields stay at proto3 defaults.
        assertEquals("", r.errorMessage)
        assertEquals(0, r.rightAudioPcm.size())
        assertFalse(r.isBuffering)

        // Encoder pin: the lite runtime serializes the same message back to
        // the same ascending-field-order bytes.
        val rebuilt = StreamResult.newBuilder()
            .setSuccess(true).setTimestampMs(1000)
            .setLeftAudioPcm(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())))
            .setSequenceNumber(5).setAudioSampleCount(1378)
            .setModelId("multisensory").setHeatmapCount(1)
            .setEnergyMap(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x10, 0x20, 0x30, 0x40)))
            .setGridWidth(14).setGridHeight(14).setWindowId(7)
            .build()
        assertArrayEquals(fixture, rebuilt.toByteArray())
    }

    @Test
    fun mu803_streamChunkWithPixelQueryFixtureDecodes() {
        // Inner PixelQuery: query_id=42, x=0.5f, y=0.25f, radius=0.5f,
        // window_id=9, sticky=true. Floats are fixed32 little-endian
        // (0.5f = 0x3F000000, 0.25f = 0x3E800000).
        val query = byteArrayOf(
            0x08, 0x2A,                                     // 1 query_id: 42
            0x15, 0x00, 0x00, 0x00, 0x3F,                   // 2 x_norm: 0.5
            0x1D, 0x00, 0x00, 0x80.toByte(), 0x3E,          // 3 y_norm: 0.25
            0x25, 0x00, 0x00, 0x00, 0x3F,                   // 4 radius_norm: 0.5
            0x28, 0x09,                                     // 5 window_id: 9
            0x30, 0x01,                                     // 6 sticky: true
        )
        val fixture = byteArrayOf(
            0x08, 0x64,                                     // 1 timestamp_ms: 100
            0x2A, 0x02, 0x01, 0x02,                         // 5 audio_pcm: 2 bytes
            0x30, 0x01,                                     // 6 is_last: true
            0x4A, 0x02, 0x03, 0x04,                         // 9 audio_pcm_hi: 2 bytes
            0x52, query.size.toByte()) + query + byteArrayOf( // 10 queries[0]
            0x58, 0x01,                                     // 11 request_clusters: true
            0x60, 0x01,                                     // 12 freeze: true
            0x68, 0x01,                                     // 13 clear_sticky: true
        )

        val c = StreamChunk.parseFrom(fixture)
        assertEquals(100L, c.timestampMs)
        assertArrayEquals(byteArrayOf(1, 2), c.audioPcm.toByteArray())
        assertTrue(c.isLast)
        assertArrayEquals(byteArrayOf(3, 4), c.audioPcmHi.toByteArray())
        assertTrue(c.requestClusters)
        assertTrue(c.freeze)
        assertTrue(c.clearSticky)
        assertEquals(1, c.queriesCount)
        val q = c.getQueries(0)
        assertEquals(42, q.queryId)
        assertEquals(0.5f, q.xNorm, 0f)
        assertEquals(0.25f, q.yNorm, 0f)
        assertEquals(0.5f, q.radiusNorm, 0f)
        assertEquals(9L, q.windowId)
        assertTrue(q.sticky)
    }

    // ── MU-804 · field-number pins via wire tags ────────────────────────

    @Test
    fun mu804_modelIdIsField15OnTheWire() {
        // (15 << 3) | LEN(2) = 0x7A — the staleness filter depends on this
        // exact field carrying the echoed id.
        val bytes = StreamResult.newBuilder().setModelId("x").build().toByteArray()
        assertArrayEquals(byteArrayOf(0x7A, 0x01, 'x'.code.toByte()), bytes)
    }

    @Test
    fun mu804_windowIdIsField24OnTheWire() {
        // (24 << 3) | VARINT(0) = 192 = 0xC0 0x01 as a two-byte tag varint.
        val bytes = StreamResult.newBuilder().setWindowId(3).build().toByteArray()
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x01, 0x03), bytes)
    }

    @Test
    fun mu804_energyGridDimensionsAreFields20And21() {
        val bytes = StreamResult.newBuilder().setGridWidth(14).setGridHeight(14).build().toByteArray()
        assertArrayEquals(byteArrayOf(
            0xA0.toByte(), 0x01, 0x0E,                      // (20<<3)|0
            0xA8.toByte(), 0x01, 0x0E,                      // (21<<3)|0
        ), bytes)
    }

    @Test
    fun mu804_audioFieldsAre5And9OnTheWire() {
        // audio_pcm (11025 Hz branch) = field 5; audio_pcm_hi (22050 Hz
        // multisensory branch) = field 9. A swap here plays speech audio at
        // half rate server-side — silent corruption, hence the byte pin.
        val lo = StreamChunk.newBuilder()
            .setAudioPcm(com.google.protobuf.ByteString.copyFrom(byteArrayOf(7))).build().toByteArray()
        val hi = StreamChunk.newBuilder()
            .setAudioPcmHi(com.google.protobuf.ByteString.copyFrom(byteArrayOf(7))).build().toByteArray()
        assertArrayEquals(byteArrayOf(0x2A, 0x01, 0x07), lo)   // (5<<3)|2
        assertArrayEquals(byteArrayOf(0x4A, 0x01, 0x07), hi)   // (9<<3)|2
    }
}
