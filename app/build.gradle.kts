plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.k1llerwhale.sonicsight"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.k1llerwhale.sonicsight"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // Android framework statics (android.util.Log, android.os.Process)
        // return stub defaults on the JVM instead of throwing "not mocked" —
        // required by the MU-2xx suite which exercises JitterBuffer off-device.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            // JaCoCo unit-test coverage (createDebugUnitTestCoverageReport).
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    // MU-3xx/4xx harness: deterministic coroutines, Flow assertions, and an
    // in-process gRPC server. Versions pinned to the app's coroutines 1.7.3
    // and grpc 1.62.2 lines.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("io.grpc:grpc-testing:1.62.2")
    testImplementation("io.grpc:grpc-inprocess:1.62.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Single-SDK Robolectric as a unit-test enabler for bitmap-backed suites
    // (MU-503/505/508/602). The multi-API Tier 0 matrix stays DEFERRED.
    testImplementation("org.robolectric:robolectric:4.16")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // MI-DEV probes: permission grant rule for camera/mic.
    androidTestImplementation("androidx.test:rules:1.6.1")
    val cameraxVersion = "1.3.1"

    //CameraX (Video & Preview)
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    // Coroutines for background processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle (ViewModel)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // PhotoView (For Zooming/Panning Images)
    implementation("com.jsibbold:zoomage:1.3.1")

    // Media3 (ExoPlayer) for Video Playback
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-common:1.2.0")

    // gRPC & Protobuf
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin.lite)
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        register("grpc") {
            artifact = libs.grpc.gen.java.get().toString()
        }
        register("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                register("grpc") {
                    option("lite")
                }
                register("grpckt") {
                    option("lite")
                }
            }
            it.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
