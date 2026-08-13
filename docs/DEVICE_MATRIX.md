# Device capability matrix (Tier 2)

One row per physical device the probes actually ran on. Devices nobody had
are **absent** — never estimated. Sources: `probe/audio_*.json` and
`probe/frame_*.json` pulled per `RUN_TESTS.md`, plus the runbook's manual
observations.

Multiple phones: `connectedDebugAndroidTest` runs on every attached device
at once — plugging them in is the whole cost (~1 min/device).

| Device | API | YUV plane geometry (rowStride / pixelStride) | MI-DEV-002 differential verdict (max/mean) | Delivered fps (unthrottled) | ≥125 ms gate passes/10 s | 44100 capture init | Effective capture Hz | Low-rate (11025/22050) init | Dual AudioTrack init | MI-DEV-003 determinism |
|---|---|---|---|---|---|---|---|---|---|---|
| *(no device run yet)* | — | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

**Status:** both probes compile (`assembleDebugAndroidTest`, 2026-08-14);
no physical device was attached in the authoring session. First fill-in
target: the primary demo phone (owner decision §4 sequence — run
MI-DEV-002 there BEFORE any `imageProxyToBitmap` fix discussion).
