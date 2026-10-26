# Decisions

Pins are updated with the git SHA after each commit that changes them.

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

## Scope

Android app plus shared library. No iOS application in this revision. iOS native targets are configured only when the host OS is macOS.
