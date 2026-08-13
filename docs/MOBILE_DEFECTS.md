# SonicSightMobile — Defect ledger

Dispositions follow the backend ledger vocabulary:
*fixed+verified* / *reclassified* / *open, bounded* / *open*.
One entry per defect, `D-M-n`, append-only. An entry is never deleted;
a wrong entry is reclassified with the reason kept.

---

## D-M-1 — FR-033 is not a mobile defect (negative result)

- **Status:** not-applicable (backend defect)
- **Date:** 2026-08-13
- **Method:** source inspection + exhaustive grep of `app/src/main/java`
  (patterns: burst/cap/sample-limit terms and the literal 16537 — the
  sample count at which 1.5 s @ 11025 Hz equals 0.75 s @ 22050 Hz).
- **Finding:** no sample-valued burst cap exists anywhere client-side. The
  mobile jitter buffer values its limits in **ms and bytes, rate-scaled
  correctly at both wire rates**: capacity
  `maxBytes = (maxBufferMs * sampleRate * 2) / 1000`
  (`JitterBuffer.kt:45`) = 33075 B @ 11025 Hz and 66150 B @ 22050 Hz with
  `maxBufferMs = 1500` (`JitterBuffer.kt:30`); prebuffer likewise ms-valued
  (`JitterBuffer.kt:29,169`). Construction sites pass the profile's
  `streamRate` explicitly (`MainActivity.kt:562-573`). The upstream send
  path has no burst limiter at all (`MainActivity.kt:1073-1117`,
  `MainViewModel.kt:250-265`).
- **Consequence:** the samples-vs-seconds confusion behind FR-033 (a cap
  sized for 11025 Hz applied at 22050 Hz → 1.5 s becomes 0.75 s) is
  demonstrably **not on the phone**; the constant lives server-side. FR-033
  is removed from the mobile pinning set (reconciliation REC-6 in
  `mobile/mobile_test_targets.yaml`); no mobile test pins or `@Ignore`s it.
