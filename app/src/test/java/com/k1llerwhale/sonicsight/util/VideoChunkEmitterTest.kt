package com.k1llerwhale.sonicsight.util

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MU-409 — VideoChunkEmitter chunking contract on the JVM through seam S8
 * (injected duration provider; mobile/mobile_test_targets.yaml MU-409).
 */
class VideoChunkEmitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val CHUNK = VideoChunkEmitter.CHUNK_SIZE   // 262144

    private fun fileOf(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }

    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun emitter(f: java.io.File, durationMs: Long = 4242L) =
        VideoChunkEmitter(f, durationProvider = { durationMs })

    @Test
    fun mu409_chunkSizeConstantIs256KiB() {
        assertEquals(262_144, CHUNK)
    }

    @Test
    fun mu409_smallFileYieldsSingleChunkWithMetadataAndIsLast() = runBlocking {
        val data = pattern(100_000)
        val f = fileOf(data)
        val chunks = emitter(f).emitChunks().toList()

        assertEquals(1, chunks.size)
        val c = chunks[0]
        assertTrue(c.hasMetadata())
        assertEquals(100_000L, c.metadata.totalSize)
        assertEquals(f.name, c.metadata.filename)
        assertEquals("video/mp4", c.metadata.mimeType)
        assertEquals(4242L, c.metadata.durationMs)
        assertEquals(0, c.chunkIndex)
        assertTrue(c.isLast)
        assertArrayEquals(data, c.data.toByteArray())
    }

    @Test
    fun mu409_exactChunkSizeFileYieldsSingleFinalChunk() = runBlocking {
        val chunks = emitter(fileOf(pattern(CHUNK))).emitChunks().toList()
        assertEquals(1, chunks.size)
        assertEquals(CHUNK, chunks[0].data.size())
        assertTrue(chunks[0].isLast)
    }

    @Test
    fun mu409_multiChunkFileMetadataOnFirstOnlyContiguousIndices() = runBlocking {
        val data = pattern(300 * 1024)                 // 307200 = 262144 + 45056
        val chunks = emitter(fileOf(data)).emitChunks().toList()

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].hasMetadata())
        assertFalse(chunks[1].hasMetadata())
        assertEquals(listOf(0, 1), chunks.map { it.chunkIndex })
        assertEquals(listOf(false, true), chunks.map { it.isLast })
        assertEquals(CHUNK, chunks[0].data.size())
        assertEquals(45_056, chunks[1].data.size())
        assertArrayEquals(data, chunks[0].data.toByteArray() + chunks[1].data.toByteArray())
    }

    @Test
    fun mu409_emptyFileEmitsZeroChunks_characterization() = runBlocking {
        // Candidate finding: no metadata chunk and no is_last marker — the
        // client-streaming RPC just half-closes with no data.
        val chunks = emitter(fileOf(ByteArray(0))).emitChunks().toList()
        assertEquals(0, chunks.size)
    }
}
