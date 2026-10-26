# Aether

Aether is an offline-first English tutor for Android, with a Kotlin Multiplatform shared library. The shared code keeps common rules for review, prompts, embeddings, and thermal limits. Android supplies the encrypted database, keystore, speech, and on-device runtime.

This repository is the app. It is not part of apex-skills.

## Install a sideload APK

There is no Play Store listing and no App Store listing.

1. Download `aether-english-release` from the GitHub Actions artifact for a `v*` tag, or build it yourself.
2. On the phone, allow install from your file manager or from Android Studio.
3. From a computer with the Android platform tools:

```bash
adb install -r app-release.apk
```

The release build in this repo is signed with the Android debug certificate so a private release keystore is not stored in git. Treat that APK as a sideload build. Replace the signing config before you ship a build you want strangers to upgrade in place.

To build locally:

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew :shared:jvmTest :androidApp:assembleDebug
```

`assembleRelease` produces the sideload APK under `androidApp/build/outputs/apk/release/`.

## What the app does

- Onboarding in English or Vietnamese, chosen from the device language (`vi` uses Vietnamese, every other language uses English). No location permission.
- Daily path, FSRS-5 review, chat, reading with a selection you can save, writing feedback, and a progress screen.
- The CEFR label on screen is an estimate for practice and leveling, not a certificate. Placement is stored only after you confirm it.
- Listening uses the device text-to-speech voice. Pronunciation records PCM on the device and, only on API 31+ when on-device recognition is available, listens with `SpeechRecognizer.createOnDeviceSpeechRecognizer`. Notes come from word alignment, RMS, clipping, and a pace heuristic. The screen does not show a speaking percent, a phoneme grade, or a CEFR speaking score. A saved PCM buffer is not sent to a server recognizer, and `EXTRA_PREFER_OFFLINE` is not used.
- Local LiteRT-LM is the first runtime. Cloud (Gemini or an OpenAI-compatible API) runs only after you save your own key. There is no silent fallback. Personal context is sent to the cloud only when a separate switch is on. That switch defaults to off.
- Model download stays disabled until `models/manifest.json` has a URL and a sha256. A local path still works and is labeled unverified.
- Seed passages, the grammar note `past-simple-regular`, and one exercise are original project text. Generated drafts are stored with the license `generated-on-device`. The draft prompt forbids copying textbooks, exams, and web articles.

## Storage and secrets

The database is SQLCipher. The passphrase and cloud keys live in the Android Keystore. The database file and the secret file are under `noBackupFilesDir`. Backup and device transfer exclude database, files, and shared preferences, and `android:allowBackup` is false. Cleartext traffic is off. Logs for a cloud call record only the vendor name and the prompt character count.

ObjectBox is not used. Its binary license does not grant redistribution for a GitHub sideload APK, and the Gradle plugin is AGPL-3.0. Vectors are exact 768-d cosine over the personal library. `VectorStore` stays an interface so an index can be swapped later.

`minSdk` is 24 because `com.google.ai.edge.litertlm:litertlm-android:0.18.0` declares `minSdkVersion="24"` in the AAR manifest (read 2026-10-26). Speech APIs from API 31 are checked at runtime. `targetSdk` is 36. `compileSdk` is 37 because Ktor 3.6 brings OkHttp 5.5, which requires it.

## Thermal defaults

These are policy caps for a Galaxy A57-class phone (Exynos 1680, 8/12 GB) before any on-device measurement. They are not measured tokens per second and not a temperature.

| Band | Max output tokens | Thinking | Speculative | Metrics | Other |
| --- | --- | --- | --- | --- | --- |
| NONE, LIGHT | 512 | 0 | only if you enable it | 5s | |
| MODERATE | 256 | 0 | off | 15s | |
| SEVERE | 128 | 0 | off | 15s | no second retrieval |
| CRITICAL, EMERGENCY | stop | 0 | off | | cancel, unload both engines, warning until the band drops below SEVERE |

Change one row after a real session on that device. `measure/prompts.txt` lists retrieval probes and does not contain a measurement.

## Not in this build

- No iOS app. `iosMain` is a stub so the shared module can grow a Keychain store later. iOS Kotlin/Native targets are created only on macOS.
- No LiteRT model file is downloaded here. The engine adapter compiles behind `LlmProvider`. Tests use a fake provider.
- No phoneme alignment and no goodness-of-pronunciation score.
- No accounts and no cloud sync.

## License

Apache License 2.0. See `LICENSE` and `NOTICE`.
