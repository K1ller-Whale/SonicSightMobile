# Physical-device runbook (Tier 2)

The only tier that proves capture. Per device: ~5 minutes automated + ~10
minutes manual. Record everything in the fill-in tables; write `NOT
OBSERVED` rather than leaving blanks.

## 0. Setup (once per device)

1. Enable Developer options → USB debugging; connect; accept the RSA prompt.
2. `adb devices` shows the device as `device` (not `unauthorized`).
3. `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`

## 1. Automated probes (MI-DEV-002 / MI-DEV-003)

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=com.k1llerwhale.sonicsight.probe"
adb pull /sdcard/Android/data/com.k1llerwhale.sonicsight/files/probe/ .\probe-results\
```

Fold the two JSONs into `DEVICE_MATRIX.md`. If MI-DEV-002 reports FAIL on
the **primary demo phone**, stop and report to the owner before touching
`imageProxyToBitmap` (plan §"Documented-defect pins" sequence).

## 2. End-to-end LAN run (the demo path)

Prereq: backend running on the GPU box (`SonicSightBackend`), phone and
box on the same LAN.

| Step | Do | Observe & record |
|---|---|---|
| E1 | Install debug APK, launch, grant camera+mic | App reaches preview |
| E2 | Set server host (long-press host dialog) to the GPU box IP | Persists across app restart (kill + relaunch) |
| E3 | Music & Instruments model, point at two sound sources, start | First result latency (progress track ≈3 s claim); overlay appears; audio in both ears |
| E4 | Headphones on: pan check | Left source in left ear, right in right (dual `AudioTrack` panning survives routing) |
| E5 | Switch to Speech mid-session | Clean cancel-and-reopen: no crash, no cross-model audio artifact, new overlay style (single map) |
| E6 | Speech: cover the camera | "No confident localization" state (confidence gate), audio keeps playing |
| E7 | Touch mode: tap a source | Region audio answer ≤ ~1 s; tapped cell highlighted |
| E8 | Touch mode: long-press (follow), then release via UI | Live follow engages; mixture returns on release |
| E9 | Touch mode: freeze, drag across cells, unfreeze | Frozen overlay static while stream continues; queries answer from the frozen scene |
| E10 | Background the app mid-stream (home button), 10 s, return | **FR-006 pin:** capture/stream continue in background (known gap — record what you observe, including the privacy indicator state) |
| E11 | Deny permissions on a fresh install | **FR-P02 pin:** toast + app exits; no rationale/recovery (declared gap) |
| E12 | Kill the backend mid-stream | Error copy says server unreachable (host named); app recovers to idle via Stop |

Fill-in:

| Step | Device | Result (pass / fail / NOT OBSERVED) | Notes |
|---|---|---|---|
| E1..E12 | | | |

## 3. Where the numbers go

Probe JSONs → `DEVICE_MATRIX.md` row. Manual observations → this file's
fill-in table (committed) + any defects → `MOBILE_DEFECTS.md` as `D-M-n`.
Every number quoted in the committee report must trace back to one of
these two artifacts.
