package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MU-403 (copy half) — stream-error classification through seam S6
 * (mobile/mobile_test_targets.yaml MU-403): the substring rules behind
 * MainActivity.describeError, pinned including precedence.
 */
class StreamErrorsTest {

    @Test
    fun mu403_modelUnavailableStrings() {
        assertEquals(StreamErrorKind.MODEL_UNAVAILABLE, classifyStreamError("model multisensory NOT LOADED"))
        assertEquals(StreamErrorKind.MODEL_UNAVAILABLE, classifyStreamError("unknown model 'x'"))
    }

    @Test
    fun mu403_serverUnreachableStrings() {
        assertEquals(StreamErrorKind.SERVER_UNREACHABLE, classifyStreamError("UNAVAILABLE: io exception"))
        assertEquals(StreamErrorKind.SERVER_UNREACHABLE, classifyStreamError("Deadline exceeded"))
        assertEquals(StreamErrorKind.SERVER_UNREACHABLE, classifyStreamError("unavailable"))
    }

    @Test
    fun mu403_genericFallback() {
        assertEquals(StreamErrorKind.GENERIC, classifyStreamError("INTERNAL: boom"))
        assertEquals(StreamErrorKind.GENERIC, classifyStreamError(""))
        assertEquals(StreamErrorKind.GENERIC, classifyStreamError(null))
    }

    @Test
    fun mu403_modelBranchWinsOverServerBranch() {
        // Precedence pinned: "not loaded" is checked before "UNAVAILABLE".
        assertEquals(StreamErrorKind.MODEL_UNAVAILABLE,
            classifyStreamError("UNAVAILABLE: model not loaded"))
    }
}
