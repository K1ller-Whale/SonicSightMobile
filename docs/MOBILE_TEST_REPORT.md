# SonicSightMobile — Test report

Status labels: `implemented+verified` (gate met, run recorded) /
`implemented` (test exists and passes, gate not fully exercisable) /
`partial` / `not-met`. Everything not measured is stated as not measured.

**Run conditions (all JVM results below):** Windows 11 host, Gradle 8.13,
AGP 8.13.2, JBR 21 (`JAVA_HOME` override — system JDK 24 incompatible),
`testDebugUnitTest`, 2026-08-14. Suite run repeatedly during development;
final full-suite state green, with earlier stability evidence per group
noted inline (MU-1xx/6xx wave: 8 consecutive green; MU-2xx wave: 8
consecutive; MU-3xx/4xx wave: 3 consecutive; full 157-test suite: 6
consecutive + coverage run). Robolectric tests: single SDK 35 (enabler,
not the deferred Tier 0 matrix).

**Suite size:** 157 unit tests — 154 executed green, 3 `@Ignore`d with
written cause (MU-508, below). Device probes (MI-DEV-002/003) compile
(`assembleDebugAndroidTest` green) — **NOT RUN: no device attached this
session.**

## Verdicts by group

| Group | Status | Notes |
|---|---|---|
| MU-101 FIR vs golden | implemented+verified | max abs error ≤ 2 LSB held for impulse+noise on both profiles |
| MU-102 passband | implemented+verified | observed halves 1 kHz **+0.0114 dB**, speech 2 kHz **−0.0027 dB**; gate ≤ 0.1 dB declared from first observation, held |
| MU-103 stopband | implemented+verified | observed **−74.53 dB** (7 kHz, halves), **−80.45 dB** (12 kHz, speech); floors −70/−75 declared from first observation, held |
| MU-104/105 DC + group delay | implemented+verified | plateau ±2 LSB; 60-sample delay via 4-phase reconstruction |
| MU-106..115 decimator battery | implemented+verified | continuity byte-identical incl. adversarial splits; MU-111/112 characterizations pinned (→ D-M-5) |
| MU-201..210 jitter buffer | implemented+verified | via seam S1; REC-1 capacity/prebuffer split confirmed; MU-206/210 characterizations (→ D-M-6, D-M-2) |
| MU-301/307 metadata | implemented+verified | header on every open incl. empty id (in-process capture) |
| MU-302/303 staleness | implemented+verified | stale drop + empty-id compat pinned |
| MU-304 switch cycle | implemented+verified | single cycle: cancel observed server-side, new metadata, stale in-flight result dropped |
| MU-305 profile table | implemented+verified | full table + invariants; byId fallback characterization (→ D-M-9) |
| MU-306 FAILED_PRECONDITION | implemented+verified | surfaces as UiState.Error carrying the status; no crash |
| MU-401 channel config | implemented | constants pinned exactly; plaintext/withoutCalls source-pinned only (builder exposes no getters) |
| MU-402 16 MB cap effect | **not measured at unit level** | in-process transport skips serialization; configured value pinned in MU-401; effect needs a socket/device run |
| MU-403 status handling | implemented+verified | characterization: raw propagation, no typed mapping (→ D-M-4); substring copy rules pinned incl. precedence |
| MU-404 cancellation swallow | implemented+verified | characterization (→ D-M-3) |
| MU-405/406 cancel/half-close | implemented+verified | server-observed close; 2-results-then-UNAVAILABLE surfaces after delivery |
| MU-407 loss policy | partial | audio lossless-in-order verified (20/20); frame path non-suspending verified; audio-parking-under-saturation **not measured** (no flow control in-process) |
| MU-408 host persistence | implemented+verified | round-trip, trim, channel rebuild, same-host no-op |
| MU-409 chunking | implemented+verified | 256 KiB contract; empty-file zero-chunk characterization (→ D-M-7) |
| MU-501/502/504 geometry | implemented+verified | **D2 pinned: 224/910 = 24.6 % portrait retention**; landscape (16,32,224,224); letterbox 224×126+49 |
| MU-503 rotations | partial | 0°/90°/180° through the real halves chain under Robolectric (test-side rotation is a pure pixel loop — Robolectric Canvas/Matrix no-ops); 270° not exercised; app-side rotation validated on hardware (MI-DEV-002) |
| MU-505 JPEG | implemented | decodes at 224×224; observed sizes 1420/1419 B (synthetic quadrant frame — size band left TBD until real-scene device data); q90 source-pinned; bar colour unverifiable under Robolectric (Canvas no-op) |
| MU-506/507 throttle | implemented+verified | **7.5 fps effective** at 30 fps delivery through the ≥125 ms gate (75 passes/10 s, exact); 33 ms gate passes every frame; no queuing residue |
| MU-508 stride matrix | **not implemented at unit level** | Robolectric's `YuvImage.compressToJpeg` emits a placeholder (decodes 100×100) — the real encoder can't run off-device. 3 tests `@Ignore`d with cause; stride confirmation moved to MI-DEV-002 (its oracle: the stride-aware reference converter) |
| MU-601 heatmap decode | implemented+verified | side inference, non-square fallback characterization (→ D-M-8), gamma anchors **v=0.5→35, v=0.3→9** (source comment claimed ~18/~4 — comment corrected), blend, silence label |
| MU-602 render dims | implemented+verified | fixed 336×336 per map regardless of side (REC-2), stitched 672×336, pixel grid×24 |
| MU-603/604 touch chain | implemented+verified | interior boundaries, exact-float boundary characterization (k/14f lands in upper cell, all k), portrait chain, non-square grids, null guards |
| MU-605 freeze/pixel protocol | partial | ViewModel half verified: query carries window_id 0 + radius + freeze/request_clusters stamps, sticky follow and clear_sticky release; the frame/audio-chunk freeze stamping lives in MainActivity capture paths — instrumented tier |
| MU-606 palette | implemented+verified | anchors, clamp, opacity, monotone luminance |
| MU-607 receive cadence | implemented+verified | folded into MU-703 tests (gap log threshold not separately asserted — logging only) |
| MU-701..704, 708 ViewModel | implemented+verified | real state set; fresh-flow no-replay; gap arithmetic exact at both rates (fallback 1250); post-cancel silence (deterministic via server-side cancel observation) |
| MU-705 FR-006 pin | **not implemented at unit level** | MainActivity's onCreate binds CameraX `ProcessCameraProvider` — no Robolectric path; FR-006 verification moves to the runbook (backgrounding step) |
| MU-706 FR-P02 pin | **not implemented at unit level** | same constraint; denial path scripted in the runbook |
| MU-707 host validation | implemented+verified | trim + empty-reject exist; **no format validation** (REC-5 addendum) — characterized |
| MU-801..804 wire contract | implemented+verified | SHA pin (cross-repo identity observed 2026-08-13), 22050 pin, hand-derived fixtures decode + encoder round-trip exact, field-number byte pins |
| MI-DEV-002/003 probes | implemented, **NOT RUN** | compile green; no device this session; DEVICE_MATRIX.md is empty by design until a run happens |

## Coverage (JaCoCo, observed 2026-08-14, debug unit tests)

Whole-module line coverage **23.4 %** (984/4207 lines) — the denominator is
dominated by generated and UI code. Per-package, same run:

| Package | Line coverage |
|---|---|
| `data/model` | **98 %** (61/62) |
| `data/api` | **73 %** (33/45) |
| `data/repository` | **70 %** (14/20) |
| `util` | **53 %** (330/618) |
| `presentation/viewmodel` | **48 %** (130/271) |
| `grpc` (generated stubs) | 19 % — excluded from any target: generated code |
| `databinding` (generated) | 0 % — generated |
| `presentation/ui` (Activities/Views) | 0 % — instrumentation-only surface; unit seams S6 extracted its pure logic (throttle, error classification) which IS covered under `util` |

No coverage target was set before measurement; the figures above are
observations, not gates. The honest reading: the logic packages sit at
48–98 %, the untestable-by-design surfaces at 0 %.

## Incidents (flaky-test ledger)

1. `mu302` failed once in a full-suite run, unreproducible in 16 subsequent
   runs, then reproduced under the coverage build with the real cause:
   `UncaughtExceptionsBeforeTest` — `runTest`'s global ledger attributing
   benign post-pass teardown noise (real-executor gRPC teardown from
   streaming tests) to the next `runTest`. Fix: direct tests moved to
   `runBlocking` (they need no virtual time; determinism comes from the
   injected dispatcher). No retry loops added anywhere.
2. Three MU-2xx test-design races (drain pre-read vs assertions) and two
   MU-3xx/7xx subscription races (replay-0 flows) were found by repeated
   runs during development and fixed by redesign, not tolerance. One of
   them exposed a real product defect en route (D-M-2 zombie revival).

## Robolectric limitations (recorded, load-bearing for verdicts above)

Robolectric 4.16 @ SDK 35 on this host: real bitmap scale/crop/JPEG
encode+decode work; `Canvas` composition ops silently no-op;
`YuvImage.compressToJpeg` produces a placeholder. Every verdict touched by
these is marked above rather than papered over.
