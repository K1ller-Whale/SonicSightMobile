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

- 🚀 **Lightning-Fast gRPC Streaming**: Utilizes bidirectional gRPC streaming to transmit raw JPEG frames and PCM audio chunks in near real-time, eliminating the need for heavy local FFmpeg encodings and ensuring blazing fast performance.
- 🧩 **Asynchronous Architecture**: Employs Kotlin Coroutines to ensure the UI thread stays completely fluid and responsive during continuous streaming sessions with the AI server.
- 🎨 **Dynamic Heatmap Visualization**: Automatically decodes, parses, and interpolates quantified heatmaps received from the server, cleanly overlaying them onto the user's video feed.
- 🎵 **Multi-Track Audio Processing**: Synthesizes and routes the separated audio tracks back into synchronized user playback effortlessly.
- 🔧 **Modern Android Practices**: Built cleanly and scalably atop MVVM (Model-View-ViewModel) architecture.

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
   By default, the Mobile App communicates to a server. You will likely need to align the IP address of the gRPC server within the `GrpcModule.kt` dependency injection configurations to point to your physical backend machine (e.g., `192.168.1.XX:50051`).

4. **Sync Gradle:**
   Allow Gradle to synchronize, automatically pulling the Protobuf definitions to generate the Java Kotlin gRPC stubs.

5. **Build and Run:**
   Select your connected device or emulator and press **Run** `(Shift + F10)` in Android Studio.

## 🛠️ gRPC and Networking

The app relies heavily on `.proto` contracts (`sonicsight.proto`) to serialize and transfer raw arrays without compressing into HTTP payloads. 
- Ensure that if changes are made to the `sonicsight.proto` defined in the backend, they are strictly copied over to the Mobile's source `proto/` directory to prevent stub mismatches. 

## 🛡️ License
Distribute your license here. All rights reserved by the original project contributors.
