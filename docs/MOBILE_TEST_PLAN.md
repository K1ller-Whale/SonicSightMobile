# SonicSightMobile — Test Plan (skeleton, Phase 1)

Status: **matrix only — no results.** Results land in `MOBILE_TEST_REPORT.md`
(Phase 6) with the four status labels
(`implemented+verified` / `implemented` / `partial` / `not-met`) and full run
conditions.

Single-source rule: gates, oracles and measurement conditions live in
[`mobile/mobile_test_targets.yaml`](../mobile/mobile_test_targets.yaml) — this
document references target ids and never restates a gate value. If a value
here ever disagrees with the YAML, the YAML wins.

Baseline: branch `test/mobile-suite` from `master` @ `56a02a5`.
Plan written: 2026-08-13.

---

## Reconciliations

Declared 2026-08-13, **before any measurement was taken**. Reason common to
all: the originating brief rows were written from a compressed project summary
rather than from source; source is authoritative. Full corrected values in the
YAML `reconciliations:` block.

| Id | Row | Correction (summary — values in the YAML only) |
|---|---|---|
| REC-1 | Jitter buffer | The brief's single figure was the ring **capacity**, not the prebuffer; the prebuffer is a separate, smaller, adaptive gate. Two gates, two rows. |
| REC-2 | Overlay | Each map is scaled to a hard-coded fixed target (no "×12 of 56"); the wide overlay exists only as the stitched halves pair; pixel mode uses a per-cell multiplier. |
| REC-3 | Proto comment | The "stale wire-rate comment" row is obsolete — the proto states the correct wire rate with the engine-internal rate noted as such. Pins kept (MU-801/MU-802). |
| REC-4 | Cadence | The speech results-per-second figure is a server cadence; no client constant exists. Client block cadence is identical on both profiles. Send tested as-is, receive characterized as-is. |
| REC-5 | MU-7xx assumptions | Real state set is Idle/Processing/Uploading/Streaming/Error/NavigationReady; persistence is SharedPreferences via GrpcModule (no SavedStateHandle); server-address validation does not exist — recorded as a gap, not a contradiction. |
| REC-6 | FR-033 | Backend defect, misattributed to mobile — mobile negative result plus the located backend constant recorded as **D-M-1** in `MOBILE_DEFECTS.md`. Removed from the mobile pinning set. |

---

## Matrix

Levels: U = unit (JVM), UR = unit under Robolectric (single SDK — enabler,
not a device tier), D = instrumented on physical hardware.
Method/oracle/gate: see the YAML entry with the same id.

### MU-1xx — Audio capture & decimation

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-101 | FR-audio-decimation (FIR vs golden) | U | JVM |
| MU-102 | FR-audio-decimation (passband) | U | JVM |
| MU-103 | FR-audio-decimation (stopband floor) | U | JVM |
| MU-104 | FR-audio-decimation (DC gain) | U | JVM |
| MU-105 | FR-audio-decimation (group delay) | U | JVM |
| MU-106 | FR-audio-decimation (state continuity) | U | JVM |
| MU-107 | Locked constant (capture-block arithmetic, halves) | U | JVM |
| MU-108 | Locked constant (speech decimation path) | U | JVM |
| MU-109 | Robustness (empty input) | U | JVM |
| MU-110 | FR-audio-decimation (remainder carry) | U | JVM |
| MU-111 | Defect-candidate (odd-byte truncation) | U | JVM |
| MU-112 | Defect-candidate (silent output drop) | U | JVM |
| MU-113 | Locked constant PCM16-LE | U | JVM |
| MU-114 | Locked constants (conversions) | U | JVM |
| MU-115 | FR-audio-decimation (ctor invariants) | U | JVM |

### MU-2xx — Jitter buffer

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-201 | REC-1 prebuffer | U | JVM |
| MU-202 | REC-1 capacity + drop-oldest | U | JVM |
| MU-203 | Drain-chunk contract | U | JVM |
| MU-204 | Underrun silence insertion | U | JVM |
| MU-205 | Adaptive prebuffer | U | JVM |
| MU-206 | Defect-candidate (partial-drain zero pad) | U | JVM |
| MU-207 | Characterization (FIFO semantics) | U | JVM |
| MU-208 | Robustness (burst clamp) | U | JVM |
| MU-209 | REC-1 byte↔ms both rates | U | JVM |
| MU-210 | Robustness (stop/start lifecycle) | U | JVM |

### MU-3xx — Model switching & staleness

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-301 | Locked constant (metadata key) | U | JVM (in-process gRPC) |
| MU-302 | Locked constant (staleness filter) | U | JVM |
| MU-303 | Characterization (empty model_id compat) | U | JVM |
| MU-304 | Locked constant (cancel-and-reopen, single cycle) | U | JVM (in-process gRPC) |
| MU-305 | Locked constants (profile table) | U | JVM |
| MU-306 | FAILED_PRECONDITION → Error | U | JVM (in-process gRPC) |
| MU-307 | NFR-COMPAT-002 (client half) | U | JVM (in-process gRPC) |

### MU-4xx — Transport

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-401 | Locked constants (channel config) | U | JVM |
| MU-402 | Locked constant (inbound size cap) | U | JVM (in-process gRPC) |
| MU-403 | Characterization + defect-candidate (status handling) | U | JVM (in-process gRPC) |
| MU-404 | Defect-candidate (CancellationException swallow) | U | JVM |
| MU-405 | NFR-resource-hygiene (cancel propagation) | U | JVM (in-process gRPC) |
| MU-406 | NFR-robustness (half-close) | U | JVM (in-process gRPC) |
| MU-407 | Locked constant (loss policy, both halves) | U | JVM |
| MU-408 | UC-06 (persistence) | U | JVM |
| MU-409 | FR-upload (chunking contract) | U | JVM |

### MU-5xx — Frame pipeline

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-501 | Locked constant (halves crop, landscape) | U | JVM |
| MU-502 | **D2 pin** (portrait FOV retention) | U | JVM |
| MU-503 | FR-frame-pipeline (rotations) | UR | Robolectric |
| MU-504 | Locked constant (letterbox geometry) | U | JVM |
| MU-505 | Locked constant (wire JPEG quality) | UR | Robolectric |
| MU-506 | Locked constant (frame-throttle gate) | U | JVM |
| MU-507 | Locked constant (keep-latest) | U | JVM |
| MU-508 | Defect-discovery (stride matrix — expected failures) | UR | Robolectric |

### MU-6xx — Heatmap overlay & touch

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-601 | Locked constant (heatmap wire decode) | UR | Robolectric |
| MU-602 | REC-2 (render dimensions) | UR | Robolectric |
| MU-603 | Locked constant (touch chain, cell boundaries) | U | JVM |
| MU-604 | UC-touch (portrait / non-square grids) | U | JVM |
| MU-605 | A2/A3 (freeze & pixel protocol) | U | JVM |
| MU-606 | UI-correctness (MagmaPalette) | U | JVM |
| MU-607 | REC-4 (receive-side handling) | U | JVM |

### MU-7xx — ViewModel & state

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-701 | REC-5 (real state machine) | U | JVM |
| MU-702 | FR-stream-lifecycle (session reset) | U | JVM |
| MU-703 | FR-playback-continuity (gap silence) | U | JVM |
| MU-704 | NFR-resource-hygiene (no post-cancel emissions) | U | JVM |
| MU-705 | **FR-006 pin** (background capture) | UR | Robolectric |
| MU-706 | **FR-P02 pin** (denial path) | UR | Robolectric |
| MU-707 | UC-06 / REC-5 (no validation; persisted) | U | JVM |
| MU-708 | FR-stream-lifecycle (buffering / failure results) | U | JVM |

### MU-8xx — Wire contract

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MU-801 | **NFR-COMPAT-001** (proto SHA-256) | U | JVM |
| MU-802 | Locked constant / REC-3 (speech wire-rate pin) | U | JVM |
| MU-803 | NFR-COMPAT-001 (golden fixtures) | U | JVM |
| MU-804 | Wire semantics pins | U | JVM |

### MI-DEV — Device probes (Tier 2 — the only tier that proves capture)

| Id | Requirement | Level | Tier |
|---|---|---|---|
| MI-DEV-002 | Frame differential oracle + cadence | D | Physical device |
| MI-DEV-003 | Audio determinism + capability matrix | D | Physical device |

Probe outputs: one JSON per device (model + API in the filename), pulled via
the one-liner in `RUN_TESTS.md`, aggregated into `DEVICE_MATRIX.md`. Devices
nobody had are **absent** from the table, never estimated.
`connectedAndroidTest` runs on every attached device at once — multiple
phones is a matter of plugging them in.

---

## Documented-defect pins (no-fix set)

| Ledger id | Pinned by | Note |
|---|---|---|
| FR-006 | MU-705 | Characterization; known gap named in test + KDoc |
| FR-P02 | MU-706 | Declared product gap, not a bug |
| D2 | MU-502 | Portrait FOV retention asserted at the ledger's figure (value in YAML) |
| ~~FR-033~~ | — | Removed per REC-6; backend anchor located — see D-M-1 |

`imageProxyToBitmap` (MU-508 / MI-DEV-002) is **not** in the no-fix set: per
owner decision the mobile no-fix rule protects no measured numbers. Sequence:
run MI-DEV-002 on the primary demo phone first; if it fails there, report
back with the diff proposal before changing anything. No silent fixes inside
test commits.

---

## Seams (approved 2026-08-13 — all eight)

Each: minimal, behaviour-preserving, constructor-defaulted, own commit,
logged in `SEAMS.md` with justification. **Introduced lazily** — each seam
lands when its suite first needs it, not all eight up front. Execution
order: MU-1xx and the pure MU-6xx/MU-305 suites first (AudioDecimator,
ModelProfile, CoordinateMap, MagmaPalette need no seams), then seam-backed
suites.

1. `JitterBuffer` — `PcmSink` interface; pure ring/drain extraction; counters `internal @VisibleForTesting`
2. `GrpcVideoRepository` — stub provider injection; `MODEL_METADATA_KEY` → internal
3. `GrpcModule` — channel factory + `HostStore` fun interface
4. `ImageTransform` — pure geometry extraction; private helpers → internal
5. `MainViewModel` — repository interface, dispatchers, clock injection; `handleStreamResult` → internal
6. `MainActivity` — pure extractions: throttle gate, chunk assembly, `describeError`, cadence math, overlay matrix
7. `MaskProcessor` — pure ByteArray→ARGB computation; `blendRgb` → internal
8. `VideoChunkEmitter` — `(File) -> Long` duration provider

---

## DEFERRED — not implemented

Listed so the plan states its own coverage. **Ask owner before starting any.**

| Item | Would cover |
|---|---|
| Load / stress / spike / soak / endurance | Sustained-throughput behaviour of stream, buffers and UI under hours-scale load |
| 100-cycle model-switch endurance | Leak/cross-profile accumulation over rapid repeated switches (single cycle stays: MU-304) |
| Sustained overproduction / backpressure saturation | Long-horizon out-queue saturation beyond the bounded assertions in MU-407/MU-208 |
| Tier 0 Robolectric multi-API matrix | API-conditional code paths minSdk 28 → targetSdk 36 |
| Tier 1 Gradle Managed Devices matrix | Emulator-reproducible instrumented runs across API levels/screens |
| Phase 5 | Accessibility, layout (RTL/font-scale/dark), performance profiling |

Emulator tiers, when built, still cannot validate real capture — emulators
fake camera and microphone. Tier 2 (MI-DEV) is the only capture-proving tier.

---

## Results

None yet. No test has produced a number as of this document's date. First
results appear in `MOBILE_TEST_REPORT.md` with conditions attached.
