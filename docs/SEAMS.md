# Production-code test seams

Every production-code change made for testability. Rules (per the brief):
minimal, behaviour-preserving, own commit, justified here. Anything not in
this file did not touch `app/src/main`.

Approved set: eight seams (MOBILE_TEST_PLAN.md "Seams"), introduced lazily —
each lands when its suite first needs it.

| Id | Change | Files | Justification | Behaviour proof |
|---|---|---|---|---|
| S1 | `PcmSink` interface + `AudioTrackSink` pass-through; `JitterBuffer` primary constructor takes `PcmSink`, secondary constructor keeps the exact `AudioTrack` signature (call sites compile unchanged); two `internal @get:VisibleForTesting` read-only observers (`underruns`, `effectiveInitialBufferMs`) | `util/PcmSink.kt` (new), `util/JitterBuffer.kt` | MU-2xx needs a deterministic, steppable sink: the drain loop is paced by the sink's blocking `write()`, and `AudioTrack` does not exist on the JVM | Same checks in the same order (`isReady` ≡ `state == STATE_INITIALIZED`, `isPlaying` ≡ `playState == PLAYSTATE_PLAYING`); `AudioTrackSink` is a 1:1 pass-through; observers are read-only |

Build-config note (not a production seam): `unitTests.isReturnDefaultValues =
true` in `app/build.gradle.kts` — Android statics (`Log`, `android.os.Process`)
no-op on the JVM instead of throwing. Test-only execution path; no effect on
any build variant's APK.
