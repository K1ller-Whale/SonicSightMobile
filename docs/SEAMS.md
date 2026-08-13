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

| S3 | `GrpcModule`: `HostStore` interface + `hostStoreOverride`, `channelFactoryOverride`, transport constants (`PORT`, `MAX_INBOUND_BYTES`, `KEEPALIVE_TIME_SECONDS`, `KEEPALIVE_TIMEOUT_SECONDS`) widened to `internal`, `channelInstance()` `private` → `internal @VisibleForTesting`, `resetForTest()` | `data/api/GrpcModule.kt` | MU-401/408 need the singleton's host persistence and channel lifecycle testable without SharedPreferences or sockets | Overrides default to null → production paths byte-identical; constants replace equal literals in the same builder calls |
| S5 | `MainViewModel` constructor-injects repository, io/main dispatchers and a clock (`@JvmOverloads` keeps the `(Application)` constructor the default factory uses); `handleStreamResult` `private` → `internal @VisibleForTesting` | `presentation/viewmodel/MainViewModel.kt` | MU-302..304/306/407/70x need deterministic dispatch and an in-process-backed repository | All defaults are the previous hard-wired values (`GrpcVideoRepository()`, `Dispatchers.IO/Main`, `System::currentTimeMillis`); creation path via default ViewModel factory unchanged |
| S4 | `ImageTransform`: pure geometry extracted (`scaledDimsShortestEdge256`, `centerCrop224Origin`, `letterboxContentDims`), bitmap functions now call them | `util/ImageTransform.kt` | MU-501/502/504 pin the crop math (incl. the D2 24.6 % retention) on the JVM | Same expressions moved verbatim; wrappers delegate — single source of the arithmetic |
| S6 | `ThrottleGate` class (interval + injected clock) extracted from the analyzer closure; `classifyStreamError`/`StreamErrorKind` extracted from `describeError` | `util/ThrottleGate.kt` (new), `util/StreamErrors.kt` (new), `presentation/ui/MainActivity.kt` | MU-506/507 need the gate under a fake clock; MU-403's copy mapping needs the substring rules pure | Gate replicates `if (now - last >= interval)` with the same initial `last = 0`; classification `when` moved verbatim, activity maps kinds to the same strings |
| S7 | `MaskProcessor`: pure pixel math into companion (`inferHeatmapDims`, `gammaAlpha`, `blendRgb`, `pixelOverlayArgb`); wrappers call them | `util/MaskProcessor.kt` | MU-601 pins decode math on the JVM (lite bitmaps unavailable) | Extracted expressions identical; one stale comment corrected to measured alpha values (35/9, was "~18/~4" — comment only, no behaviour) |

Build-config note (not a production seam): `unitTests.isReturnDefaultValues =
true` in `app/build.gradle.kts` — Android statics (`Log`, `android.os.Process`)
no-op on the JVM instead of throwing. Test-only execution path; no effect on
any build variant's APK.
