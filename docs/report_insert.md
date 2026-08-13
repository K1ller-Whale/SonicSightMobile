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

## Test-plan rows (Table 5 shape)

NOT YET WRITTEN — appended in Phase 6 from `MOBILE_TEST_REPORT.md`, one row
per suite: layer · method · result, with the four status labels.

## Numbers produced, with conditions

NOT YET MEASURED — appended in Phase 6. Every entry will carry device/API/
build type/run count, or the JVM run conditions from
`mobile/mobile_test_targets.yaml` `conditions:`.
