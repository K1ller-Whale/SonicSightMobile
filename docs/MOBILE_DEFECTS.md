# SonicSightMobile — Defect ledger

Dispositions follow the backend ledger vocabulary:
*fixed+verified* / *reclassified* / *open, bounded* / *open*.
One entry per defect, `D-M-n`, append-only. An entry is never deleted;
a wrong entry is reclassified with the reason kept.

---

## D-M-1 — FR-033 is not a mobile defect (negative result + backend anchor)

- **Status:** reclassified (not applicable to mobile — backend defect)
- **Date:** 2026-08-13
- **Method:** source inspection + exhaustive grep of the mobile tree
  (`app/src/main/java`; patterns: burst/cap/sample-limit terms and the
  literal 16537 — the sample count at which 1.5 s @ 11025 Hz equals
  0.75 s @ 22050 Hz), followed by a targeted grep of `SonicSightBackend/src`
  to locate the actual constant.
- **Finding (mobile, negative):** no sample-valued burst cap exists anywhere
  client-side. The mobile jitter buffer values its limits in **ms and
  bytes, rate-scaled correctly at both wire rates**: capacity
  `maxBytes = (maxBufferMs * sampleRate * 2) / 1000`
  (`JitterBuffer.kt:45`) = 33075 B @ 11025 Hz and 66150 B @ 22050 Hz with
  `maxBufferMs = 1500` (`JitterBuffer.kt:30`); prebuffer likewise ms-valued
  (`JitterBuffer.kt:29,169`). Construction sites pass the profile's
  `streamRate` explicitly (`MainActivity.kt:562-573`). The upstream send
  path has no burst limiter at all (`MainActivity.kt:1073-1117`,
  `MainViewModel.kt:250-265`).
- **Finding (backend, anchor):** the sample-valued cap lives server-side:
  `SAFE_DRAIN_SAMPLES = int(AUD_RATE * 1.5)  # 16537 samples = jitter cap`
  (`SonicSightBackend/src/grpc_server.py:354`) with `AUD_RATE = 11025`
  (`SonicSightBackend/src/config.py:14`). The cap is a fixed 16537 samples
  applied to every model branch's OLA drain (`grpc_server.py:355-363`); on
  the multisensory branch's 22050 Hz output, 16537 samples = 0.75 s — the
  exact FR-033 shape (a limit sized for 11025 Hz, valued in samples,
  applied at the higher rate).
- **Consequence:** the samples-vs-seconds confusion behind FR-033 is
  demonstrably not on the phone. FR-033 is removed from the mobile pinning
  set (reconciliation REC-6 in `mobile/mobile_test_targets.yaml`); no
  mobile test pins or `@Ignore`s it. Both findings are source-inspection
  results, not runtime measurements.

---

## D-M-2 — JitterBuffer drain thread survives stop() and revives into the next start()

- **Status:** open, bounded
- **Date:** 2026-08-13
- **Method:** JVM unit testing through seam S1 (deterministic fake sink);
  behaviour observed live during MU-2xx test development, then pinned.
- **Finding:** `stop()` joins the drain thread with a 500 ms timeout
  (`JitterBuffer.kt:100`) and proceeds regardless. A thread stalled inside
  the sink's blocking `write()` (production: `AudioTrack.write`) survives
  the join, and because it re-checks the shared `running` flag on its next
  loop iteration, a subsequent `start()` that has already set
  `running = true` **revives the old thread alongside the new one** — two
  drainers on one ring buffer. Observed deterministically on the JVM: the
  revived thread consumed ring bytes belonging to the new stream
  (originally surfaced as a test-design race in the MU-205 restart test;
  reproduced and pinned by
  `mu210_stopWithStalledSinkLeavesZombieDrainThread_characterization`).
- **Bound:** requires the sink write to stall longer than the 500 ms join
  at exactly stop() time, and a restart while the old thread is still
  parked — plausible when `AudioTrack.write` blocks on a full track buffer
  during teardown (e.g. rapid model switch). Not observed on hardware; no
  runtime measurement claimed.
- **Disposition rationale:** *open, bounded* — pinned by a
  characterization test; no fix applied (no-fix default; a fix would need
  owner approval per the mobile fix policy).

---

## D-M-3 — processVideo converts cancellation into an upload failure

- **Status:** open
- **Date:** 2026-08-14 · **Pinned by:** `mu404` (GrpcTransportTest)
- `catch (e: Exception)` in `GrpcVideoRepository.processVideo` swallows
  `CancellationException` and returns `Result.failure` — cancelling the
  caller reads as "upload failed" instead of propagating, breaking
  structured concurrency on the legacy upload path.

## D-M-4 — no typed gRPC status mapping; error copy rides substring matching

- **Status:** open, bounded
- **Date:** 2026-08-14 · **Pinned by:** `mu403`, `StreamErrorsTest`
- `streamProcess` has no error handling; raw `StatusException` strings
  reach `UiState.Error`, and the user-facing copy is chosen by substring
  rules (`classifyStreamError`). Bounded: the rules cover the common
  UNAVAILABLE / not-loaded / deadline cases and are now pinned, including
  precedence. Unused `catch`/`map` imports in the repository suggest
  mapping was intended.

## D-M-5 — decimator silently drops output on undersized buffers and odd byte counts

- **Status:** open, bounded
- **Date:** 2026-08-14 · **Pinned by:** `mu111`, `mu112`
- An output buffer smaller than `maxOutputBytes()` loses samples with no
  error while filter state advances; an odd `lengthBytes` silently drops
  the trailing byte with no carry. Bounded: the single production call
  site sizes via `maxOutputBytes` and `AudioRecord` delivers even counts.

## D-M-6 — jitter buffer injects zero-padding on partial drains

- **Status:** open, bounded
- **Date:** 2026-08-14 · **Pinned by:** `mu204_mu206` (JitterBufferTest)
- A partial read still writes a full 512-byte chunk, zero-padded (up to
  511 silence bytes mid-stream, distinct from the documented underrun
  silence). Bounded: occurs only when the ring holds < 512 B.

## D-M-7 — empty upload file emits zero chunks

- **Status:** open, bounded
- **Date:** 2026-08-14 · **Pinned by:** `mu409_emptyFileEmitsZeroChunks`
- `VideoChunkEmitter` on a 0-byte file emits no metadata chunk and no
  `is_last`: the client-streaming RPC half-closes with no data. Bounded:
  legacy upload path only.

## D-M-8 — malformed heatmap payloads render as 56×56 fallback

- **Status:** open, bounded
- **Date:** 2026-08-14 · **Pinned by:** `mu601_nonSquareByteCount...`
- A non-square byte count silently falls back to 56×56 (zero-padded)
  instead of failing — a truncated wire payload draws garbage rather than
  surfacing an error. Bounded: requires a malformed server payload.

## D-M-10 — imageProxyToBitmap assumes packed NV21 plane layout

- **Status:** open (suspected impact, unconfirmed on hardware)
- **Date:** 2026-08-14 · **Source anchor:** `ImageTransform.kt` (buffer
  concatenation ignores `rowStride`/`pixelStride`; plane order assumed
  V-then-U interleaved)
- The conversion copies Y/V/U plane buffers back-to-back. That survives
  the common semi-planar (NV21-underlying) layout and breaks on padded
  rowStride or truly planar (pixelStride 1) devices — corrupted frames for
  every model. Unit-level confirmation was attempted (stride-matrix tests,
  MU-508) and is infeasible off-device: Robolectric's `YuvImage` encoder is
  a placeholder, so the three tests are `@Ignore`d with cause. The
  stride-aware reference converter ships inside MI-DEV-002, which settles
  this per device; fix decision belongs to the owner AFTER the demo-phone
  run (plan §"Documented-defect pins" sequence).

## D-M-9 — unknown model id silently falls back to the default profile client-side

- **Status:** open, bounded
- **Date:** 2026-08-14 · **Pinned by:** `mu305_byIdUnknownFallsBack...`
- `ModelProfile.byId` returns DEFAULT for unknown/empty ids while the
  server rejects unknown ids with `FAILED_PRECONDITION` — the two ends
  disagree about what a bad id means. Bounded: ids originate from the
  in-app selector today.
