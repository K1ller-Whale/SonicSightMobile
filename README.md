# 📱 SonicSight Mobile

<div align="center">
  <h3>Mobile Client for Real-Time Audio-Visual Source Separation</h3>
  <i>Powered by Android, Kotlin, and gRPC</i>
</div>

---

## 📖 Overview

SonicSight Mobile is the Android client application that interfaces seamlessly with the SonicSight AI backend. It captures local media securely and transfers it to the server through a high-speed gRPC stream. It then gracefully visualizes the returned localized sound heatmaps alongside the separated audio streams. 

By dynamically offloading heavy PyTorch AI processing to the server while retaining smooth UI presentation and media synchronization on the device locally, the app provides an engaging and magical experience of "seeing" sound.

## ✨ Features

- 🎚️ **Three ways to listen**: **Left–Right** (Sound of Pixels — splits the
  scene down the middle, two tracks, nothing to tap), **Touch** (same
  checkpoint — hear any point you pick on the model's real 14×8 grid, with
  discovered sources as CVD-safe colour dots you can solo, plus Freeze to
  explore a held scene), and **Speech** (multisensory — on-screen vs
  off-screen). Each is a `ModelProfile`; selection rides the
  `sonicsight-model` gRPC metadata key, switching always cancels and reopens
  the stream, and results whose echoed `model_id` doesn't match are dropped.
  The touch coordinate chain (view → inverse fillCenter → letterbox → grid
  cell) lives in `CoordinateMap` with JVM unit tests.
- 🚀 **Bidirectional gRPC streaming**: raw JPEG frames + PCM chunks up, separated
  audio + heatmaps back, continuously.
- 🎨 **Spectral heatmap overlay**: server heatmaps (56×56 uint8 on the wire,
  grid size inferred from byte count) rendered in the magma colormap —
  perceptually uniform and colour-vision-deficiency-safe — with an on-screen
  legend saying what the colours mean for the active model.
- 🎵 **Separated playback with solo control**: chips labelled from the model
  profile (Left/Right or On-screen/Off-screen) solo either stream; playback
  sample rate follows the profile (11025 / 22050 Hz).
- 🧭 **Honest states**: buffering with the model's real expected first-result
  time, "no confident on-screen source" when the speech model's confidence
  gate trips, and errors that say what to do next (unreachable server names
  the host; unloaded model suggests the other one).
- 🧩 **MVVM + Coroutines**, XML Views with ViewBinding.

## 🏗️ Project Architecture

The app is built keeping modern Android development patterns in mind:

- **View** (`presentation/ui`): Core activities handling standard rendering (`MainActivity`, `ResultActivity`).
- **ViewModel** (`presentation/viewmodel`): Employs `MainViewModel` to cache state independently from the lifecycle.
- **Repository** (`data/repository`): `GrpcVideoRepository` governs incoming and outgoing stream connections abstractly.
- **Util** (`util/`): Houses critical media handlers, including processing chunks (`VideoChunkEmitter`), managing Android buffers (`MediaUtils`), processing server heatmap overlays (`MaskProcessor`), and executing image projections (`ImageTransform`).
- **gRPC API** (`data/api`): Handles the actual `GrpcModule` and connection stubs bound to the defined protobuf interface.

## 🗂️ Directory Structure

```text
SonicSightMobile/
├── app/
│   ├── src/main/java/com/k1llerwhale/sonicsight/
│   │   ├── data/                 # gRPC module, proto stubs, and network repositories
│   │   ├── presentation/         # ViewModels and UI Activities
│   │   └── util/                 # Processing utilities for audio chunks, media, and heatmaps
│   ├── src/main/proto/           # Protobuf definitions shared with the backend
│   └── src/main/AndroidManifest.xml
├── build.gradle.kts              # Application build configurations
└── settings.gradle.kts           # Module structure settings
```

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (or preferred current stable release).
- Minimum Android SDK configuration (Check `build.gradle.kts`, usually API 24+).
- The **SonicSight Backend** running on an accessible IP address/network.

### Installation & Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd SonicSightV1/SonicSightMobile
   ```

2. **Open the Project:**
   Open Android Studio and select **File -> Open**, navigating to the `SonicSightMobile` directory. 

3. **Configure the IP Address:**
   Tap the ⚙ button in the app and enter your backend machine's IP or hostname
   (port is fixed at 50051). The value persists in SharedPreferences;
   `GrpcModule.kt` only holds the compiled-in default.

4. **Sync Gradle:**
   Allow Gradle to synchronize, automatically pulling the Protobuf definitions to generate the Java Kotlin gRPC stubs.

5. **Build and Run:**
   Select your connected device or emulator and press **Run** `(Shift + F10)` in Android Studio.

## 🛠️ gRPC and Networking

The app relies heavily on `.proto` contracts (`sonicsight.proto`) to serialize and transfer raw arrays without compressing into HTTP payloads.
- The contract lives at the **backend repo root** (`SonicSightBackend/sonicsight.proto`);
  this repo keeps a **byte-identical** copy at `app/src/main/proto/sonicsight.proto`.
  If you change one, change the other in the same change set — Gradle regenerates
  the Kotlin/Java stubs on build.
- Model selection is per-stream gRPC metadata (`sonicsight-model`), echoed back in
  every `StreamResult.model_id`. See `SonicSightBackend/MODELS.md` for how to add
  a model end to end, and `SonicSightBackend/TESTPLAN.md` for the validation plan.

## 🛡️ License
Distribute your license here. All rights reserved by the original project contributors.
