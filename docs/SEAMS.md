# Production-code test seams

Every production-code change made for testability. Rules (per the brief):
minimal, behaviour-preserving, own commit, justified here. Anything not in
this file did not touch `app/src/main`.

Approved set: eight seams (MOBILE_TEST_PLAN.md "Seams"), introduced lazily —
each lands when its suite first needs it.

| Id | Change | Files | Justification | Behaviour proof |
|---|---|---|---|---|
| S1 | `PcmSink` interface + `AudioTrackSink` pass-through; `JitterBuffer` primary constructor takes `PcmSink`, secondary constructor keeps the exact `AudioTrack` signature (call sites compile unchanged); two `internal @get:VisibleForTesting` read-only observers (`underruns`, `effectiveInitialBufferMs`) | `util/PcmSink.kt` (new), `util/JitterBuffer.kt` | MU-2xx needs a deterministic, steppable sink: the drain loop is paced by the sink's blocking `write()`, and `AudioTrack` does not exist on the JVM | Same checks in the same order (`isReady` ≡ `state == STATE_INITIALIZED`, `isPlaying` ≡ `playState == PLAYSTATE_PLAYING`); `AudioTrackSink` is a 1:1 pass-through; observers are read-only |

| S2 | `GrpcVideoRepository` constructor-injects a stub provider (default `{ GrpcModule.stub }`) and a chunk-flow factory (default `VideoChunkEmitter(it).emitChunks()`); `MODEL_METADATA_KEY` `private` → `internal @VisibleForTesting` | `data/repository/GrpcVideoRepository.kt` | MU-3xx/4xx need the repository against an in-process gRPC server and synthetic chunk flows; the static `GrpcModule.stub` coupling made both impossible | Defaults reproduce the exact previous expressions; `GrpcVideoRepository()` call sites compile and behave unchanged; the key constant is read-only |
| S8 | `VideoChunkEmitter` constructor-injects `durationProvider: (File) -> Long` defaulting to the original `MediaMetadataRetriever` implementation (moved verbatim into the companion) | `util/VideoChunkEmitter.kt` | MU-409 tests the chunking contract on the JVM, where `MediaMetadataRetriever` does not exist | Default path is the same code, same call timing (invoked once per `emitChunks()` collection start); `VideoChunkEmitter(file)` call sites compile unchanged |

Build-config note (not a production seam): `unitTests.isReturnDefaultValues =
true` in `app/build.gradle.kts` — Android statics (`Log`, `android.os.Process`)
no-op on the JVM instead of throwing. Test-only execution path; no effect on
any build variant's APK.
