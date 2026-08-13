# Committee report insert — mobile verification campaign

Paste-ready block for the committee report. Plain English, ready for
translation. Every number in this file traces to a named source line or a
recorded run; anything not yet measured is marked NOT MEASURED. Test-plan
rows (Table 5 shape: layer · method · result) and the run-numbers list are
appended in Phase 6.

---

## Defect-ledger addition: FR-033 root cause located (source anchor)

FR-033 was reported during the backend campaign as: the speech-model burst
cap holds 0.75 s of audio, not the designed 1.5 s. The mobile verification
campaign located the defect's exact mechanism, by source inspection
(2026-08-13, no runtime measurement required):

- The constant: `SAFE_DRAIN_SAMPLES = int(AUD_RATE * 1.5)` —
  `SonicSightBackend/src/grpc_server.py:354`, with `AUD_RATE = 11025`
  (`SonicSightBackend/src/config.py:14`).
- The derivation: `int(11025 × 1.5) = 16537` samples. The limit is valued
  in **samples**, sized once from the 11025 Hz branch. Applied unchanged to
  the multisensory branch, whose output rate is 22050 Hz:
  `16537 / 22050 = 0.75 s`. The designed behaviour (1.5 s) holds only at
  11025 Hz.
- Scope of the cap: it bounds **per-window OLA drains** on the shared
  streaming path (`grpc_server.py:355-363`). It does **not** apply to the
  final overlap-add tail flush at stream close: `left_ola.flush()` /
  `right_ola.flush()` (`grpc_server.py:495-496`) are yielded untrimmed
  (`grpc_server.py:505-519`). The report's claim of a full tail flush then
  a clean close is therefore consistent with source. A final flush longer
  than the phone's 1.5 s jitter-buffer capacity is clamped client-side by
  the buffer's drop-oldest policy (`JitterBuffer.kt:124-141`) — the start
  of the tail is dropped cleanly, with no corruption.
- Corroborating detail: the same file already contains the corrected form
  of this constant on the pixel-mode path —
  `SAFE_DRAIN_SAMPLES = int(spec.output_sample_rate * 1.5)`
  (`grpc_server.py:608`), which rate-scales and is not affected by FR-033.
- Mobile-side negative result: exhaustive inspection of the mobile tree
  found **no** sample-valued cap client-side; the phone's jitter buffer
  values its limits in ms and bytes, rate-scaled correctly at both wire
  rates (33075 B @ 11025 Hz, 66150 B @ 22050 Hz —
  `JitterBuffer.kt:30,45`). The samples-vs-seconds confusion is exclusively
  server-side. Ledger entry: `docs/MOBILE_DEFECTS.md` D-M-1, disposition
  *reclassified*.

---

## Test-plan rows (Table 5 shape: layer · method · result)

| Layer | Method | Result |
|---|---|---|
| Audio decimation (FIR ÷4/÷2) | JVM unit vs independent NumPy golden vectors, 2-LSB gate; analytic tone tests | implemented+verified — 26 tests; state continuity byte-identical under adversarial chunking |
| Jitter buffer | JVM unit through a deterministic sink seam (semaphore-stepped drain) | implemented+verified — 15 tests; capacity/prebuffer split confirmed; 1 new defect found and pinned (D-M-2) |
| Model switching & staleness | JVM unit against an in-process gRPC server (real generated stubs) | implemented+verified — cancel-and-reopen observed server-side; stale echoed-model results provably dropped |
| Transport | in-process gRPC + seams; wire-tag byte pins | implemented+verified with two stated unit-level gaps (16 MB cap effect; audio-parking under saturation) — both recorded as not measured, not assumed |
| Frame pipeline geometry | JVM unit on extracted pure math; Robolectric (single SDK) for bitmap paths | implemented+verified — D2 portrait FOV loss pinned at 224/910 = 24.6 % |
| Heatmap & touch | JVM unit (decode math, coordinate chain incl. float-boundary characterization); Robolectric render dims | implemented+verified |
| ViewModel & state | JVM unit through injected dispatchers/clock/repository | implemented+verified — real state machine, gap-silence arithmetic exact at both wire rates |
| Wire contract | SHA-256 cross-repo pin; hand-derived wire-format fixtures | implemented+verified |
| Device probes (capture proof) | instrumented differential oracle + audio capability matrix | implemented, NOT RUN — awaits the demo phone (matrix empty by design) |

## Numbers produced, with conditions

All JVM figures: Windows 11, Gradle 8.13/AGP 8.13.2, JBR 21,
`testDebugUnitTest`, 2026-08-13/14; deterministic tests, stability
evidenced by repeated full-suite runs (6-8 consecutive green per wave).

- Unit suite: **157 tests, 154 executed green, 3 @Ignored with written
  cause** (Robolectric cannot run the real YUV→JPEG encoder).
- FIR passband deviation, observed then declared as gates: halves 1 kHz
  **+0.0114 dB**; speech 2 kHz **−0.0027 dB** (gate ≤ 0.1 dB).
- FIR stopband attenuation, observed then declared: halves 7 kHz
  **−74.53 dB**; speech 12 kHz **−80.45 dB** (floors −70/−75 dB).
- Frame throttle at 30 fps delivery through the ≥125 ms gate: **75 passes
  per 10 s = 7.5 fps effective** (exact, fake-clock simulation) — the
  nominal "8 fps" is the interval's inverse, not the delivered rate.
- Portrait halves vertical FOV retention (defect D2, pinned): **224/910 =
  24.6 %**.
- Gamma-alpha anchors (source comment corrected): v=0.5 → **35**, v=0.3 →
  **9** (ceiling 200).
- Line coverage (JaCoCo, observed, no pre-set target): whole module
  23.4 %; logic packages: data/model **98 %**, data/api **73 %**,
  repository **70 %**, util **53 %**, viewmodel **48 %**; generated code
  (grpc stubs, databinding) and Activity/View UI excluded from any claim —
  0–19 % by design, stated as such.
- Mobile defect ledger: **D-M-1..D-M-9** (1 reclassified with backend
  anchor, 1 open, 7 open-bounded), every entry pinned by a named test or
  source line.
- Devices measured so far: **none** — `DEVICE_MATRIX.md` is empty by
  design until the probes run on real hardware.
