# Third-party notices

The SimNest gateway is licensed under the GNU General Public License, version 3 (see `LICENSE`). It uses the
components below, each under its own licence.

## Source included in this repository

| Component | Version | Licence | Where |
| --- | --- | --- | --- |
| libplum | v0.6.0, one file changed (see `CLOUDSIM.md` there) | MPL-2.0 | `gateway/src/main/cpp/third_party/libplum/` |
| scrcpy (shell identity code adapted from its `Workarounds` and `FakeContext`) | v5.0.1 | Apache-2.0 | `gateway/src/main/java/dev/cloudsim/gateway/shell/BridgeMain.java`; licence text in `gateway/src/main/assets/licenses/scrcpy-LICENSE.txt` |

`gateway/src/main/java/com/flyfish233/crypto/spake2/` and `gateway/src/main/cpp/spake2.cpp` are this project's own
code. They carry the class names Kadb's pairing calls and are backed by BoringSSL; nothing of the library that
normally sits under those names is used.

## Libraries built into the app

| Component | Version | Licence |
| --- | --- | --- |
| AndroidX (core-ktx, activity-compose, lifecycle), Jetpack Compose, Material 3 | core-ktx 1.19.1, activity-compose 1.13.0, lifecycle 2.11.0, Compose BOM 2026.09.00 | Apache-2.0 |
| Kotlin standard library and coroutines | 2.4.21 | Apache-2.0 |
| stream-webrtc-android (GetStream), a build of WebRTC | 1.3.10 | Apache-2.0; WebRTC itself is under a BSD-style licence |
| Kadb | 2.1.5 | Apache-2.0 |
| Bouncy Castle (bcprov, bcpkix, bcutil), through Kadb | 1.86 | Bouncy Castle Licence (MIT-style) |
| Okio, through Kadb | 3.18.2 | Apache-2.0 |
| BoringSSL prebuilt (`io.github.vvb2060.ndk:boringssl`) | 20251124 | Apache-2.0 |
| Tink (`tink-android`) and what it brings (gson, error_prone_annotations, annotation-jvm, jsr305) | 1.24.0 | Apache-2.0; jsr305 is BSD-3-Clause |
| ZXing core | 3.5.4 | Apache-2.0 |
| Apache Commons Compress | 1.27.1 | Apache-2.0 |
| sherpa-onnx, with ONNX Runtime linked in | 1.13.8 | Apache-2.0; ONNX Runtime is MIT |

Licence texts kept with the app and shown on its About page: `gateway/src/main/assets/licenses/` (`INDEX.txt` says which text covers which component).

Only in tests, not in the app: JUnit 4.13.2 (EPL-1.0), org.json 20260814 (public domain).

## Models the gateway downloads when its assistant is set up

Not part of this repository or of the app package. The gateway downloads them from the sherpa-onnx project's GitHub
releases onto the phone it runs on and checks their SHA-256:

- Silero VAD, SenseVoice (speech to text), ZipVoice (speech in the owner's cloned voice, trained on the Emilia
  dataset), a Vocos vocoder and a GTCRN denoiser.

Each model has its own terms, set by its authors; some limit commercial use. They are used on the user's own phone.

## Build tools

Gradle wrapper (Apache-2.0), Android Gradle Plugin (Apache-2.0), Kotlin Compose compiler plugin (Apache-2.0).
