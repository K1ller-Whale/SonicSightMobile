# Running the SonicSightMobile test suite (PowerShell)

Every command below was run and verified on 2026-08-14 (Windows 11,
Gradle 8.13, AGP 8.13.2).

## Prerequisite — JDK

The system JDK 24 breaks Gradle 8.13 ("Type T not present" configuring
test tasks). Point Gradle at Android Studio's JBR 21 once per shell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
```

(Adjust to your Android Studio install; any JDK 17–21 works.)

## All unit tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Duration observed: ~40 s warm, ~2 min cold daemon. Result XML:
`app\build\test-results\testDebugUnitTest\` — HTML:
`app\build\reports\tests\testDebugUnitTest\index.html`.

Expected: all green; 3 tests `@Ignore`d with cause (MU-508 — Robolectric's
`YuvImage` placeholder; see `MOBILE_TEST_REPORT.md`).

## One suite

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.k1llerwhale.sonicsight.util.AudioDecimatorTest"
```

Class names: `AudioDecimatorTest`, `JitterBufferTest`, `ModelProfileTest`,
`CoordinateMapTest`, `CoordinateMapBoundaryTest`, `MagmaPaletteTest`,
`MaskProcessorPureTest`, `ImageTransformGeometryTest`, `ThrottleGateTest`,
`StreamErrorsTest`, `FramePipelineRobolectricTest` (Robolectric, SDK 35),
`VideoChunkEmitterTest`, `GrpcModuleTest`, `GrpcTransportTest`,
`WireContractTest` (package `grpc`), `MainViewModelTest` (package
`presentation.viewmodel`).

## Coverage (JaCoCo)

```powershell
.\gradlew.bat :app:createDebugUnitTestCoverageReport
```

HTML: `app\build\reports\coverage\test\debug\index.html`; XML `report.xml`
beside it. Figures and exclusion rationale: `MOBILE_TEST_REPORT.md`.

## Device probes (Tier 2 — needs a connected phone, USB debugging on)

Compile check without a device:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
```

Run both probes on every connected device (multiple phones = plug them all
in; `connectedDebugAndroidTest` fans out to each):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.package=com.k1llerwhale.sonicsight.probe"
```

Pull the JSON results (one file per probe per device):

```powershell
adb shell "ls /sdcard/Android/data/com.k1llerwhale.sonicsight/files/probe/" ; adb pull /sdcard/Android/data/com.k1llerwhale.sonicsight/files/probe/ .\probe-results\
```

Fold the JSONs into `docs/DEVICE_MATRIX.md` (one row per device; devices
nobody ran stay absent — never estimated). Under a minute per borrowed
phone: plug in, run the two commands above, unplug.

## Not covered by any command here

Tier 0 multi-API Robolectric matrix and Tier 1 Gradle Managed Devices are
DEFERRED (see `MOBILE_TEST_PLAN.md`). The E2E LAN run against the real
backend is manual: `docs/PHYSICAL_DEVICE_RUNBOOK.md`.
