package com.k1llerwhale.sonicsight.util

/**
 * Frame-rate gate: passes at most one event per [intervalMs], measured on
 * the injected [clock]. Seam S6 (docs/SEAMS.md) — extracted verbatim from
 * MainActivity's analyzer closure so MU-506/507 can drive it with a fake
 * clock: `if (now - last >= interval) { pass; last = now }`.
 *
 * Not thread safe; the camera analyzer calls it from a single executor,
 * exactly like the original closure's captured variable.
 */
class ThrottleGate(
    private val intervalMs: Long,
    private val clock: () -> Long,
) {
    private var lastPassedAt = 0L

    /** True if this event passes the gate (and consumes the interval). */
    fun tryPass(): Boolean {
        val now = clock()
        if (now - lastPassedAt >= intervalMs) {
            lastPassedAt = now
            return true
        }
        return false
    }
}
