# Decisions

Pins are updated with the git SHA after each commit that changes them.

Implementation commit: `7c401994869e4d123bbda3c85627f9ef13df5830`.

## 2026-10-26 versions

- Kotlin 2.4.21, because LiteRT-LM 0.18.0 carries Kotlin 2.4 metadata.
- Android Gradle Plugin 9.3.1 from Google Maven. This is the newest AGP that Kotlin 2.4.21 lists as fully supported (range 8.5.2–9.3.1). `apex-versions` resolved `com.android.tools.build:gradle` to 2.3.0 from Maven Central, which does not see current Google Maven artifacts. That resolver result is rejected.
- The shared module uses `com.android.kotlin.multiplatform.library`. AGP 9 rejects applying `com.android.library` next to the Kotlin Multiplatform plugin.
- Gradle 9.7.0.
- compileSdk 37 and targetSdk 36. minSdk 24, from the LiteRT-LM 0.18.0 AAR `uses-sdk`. Ktor 3.6 pulls OkHttp 5.5, whose Android artifact requires compileSdk 37. targetSdk stays 36, the stable platform chosen when this project was specified.
- Compose BOM 2026.09.00, activity-compose 1.13.0, navigation-compose 2.10.2, lifecycle 2.11.0, coroutines 1.11.0, Ktor 3.6.0, SQLCipher `net.zetetic:sqlcipher-android` 4.19.1.

## Store

ObjectBox is rejected. The binary license (fetched 2026-10-26) allows use inside applications but is non-transferable and non-sublicensable, and it does not grant redistribution for a public sideload APK. The Gradle plugin POM for 6.0.0-beta is AGPL-3.0. The replacement is SQLCipher plus exact 768-d cosine in common code, behind `VectorStore`.

## Thermal policy

Defaults for a Galaxy A57-class device before measurement: 512 / 256 / 128 output-token caps across NONE-LIGHT, MODERATE, and SEVERE; stop and unload both engines at CRITICAL and EMERGENCY; thinking stays 0; speculative decoding stays off unless the learner enables it, and only on NONE or LIGHT. These are caps, not measured throughput.

## Distribution

GitHub plus a sideload APK. No Play Store and no App Store. The release workflow signs with the debug certificate because a release keystore is a secret and is not committed.

## On-device model files

Checked against the public Hugging Face API on 2026-10-26. The checksum is not stored here. The app reads the Git LFS oid when a download starts.

- Chat: `litert-community/gemma-4-E2B-it-litert-lm` revision `1caa527c1e88b55f046fcd8d2b4265606680663d`, file `gemma-4-E2B-it.litertlm`, 2588147712 bytes. Not gated. This is the general LiteRT-LM build. The CPU/GPU switch selects the runtime backend for this one file.
- Embedding: `litert-community/embeddinggemma-2-740m-litert-lm` revision `24d962e906c7d332c6428e71c9676855024569e2`, file `embeddinggemma-2-740m.litertlm`, 484622336 bytes. Not gated. The model card states a 768-dimensional vector space, which matches the stored embedding width. `litert-community/embeddinggemma-300m` is gated and ships `.tflite`, which this LiteRT-LM engine does not load.

## Scope

Android app plus shared library. No iOS application in this revision. iOS native targets are configured only when the host OS is macOS.
