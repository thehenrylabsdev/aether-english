# Constraints

- Apache-2.0 only for Aether source. Do not add ObjectBox or any AGPL plugin.
- `minSdk` stays 24 while the pinned LiteRT-LM Android artifact declares that floor. Do not raise it to unlock speech APIs; guard those at runtime.
- Embeddings are 768 dimensions. Reject any other length before it is stored.
- Review scheduling is FSRS-5. Do not reintroduce SM-2.
- The model must not write the profile or the review queue. `UpdateProgress` is callable only by the app.
- Do not log secrets, Authorization headers, or prompt text that includes a key. A cloud log line is vendor plus character count.
- Do not send personal context to a cloud vendor unless the learner's switch is on. Do not fall back from cloud to local, or the reverse, without an explicit choice.
- Do not show a pronunciation percent, a phoneme grade, or a CEFR speaking score. Do not call a server speech recognizer. Do not treat `EXTRA_PREFER_OFFLINE` as on-device recognition.
- Do not put third-party textbook, exam, or article text in the seed catalog.
- Do not publish a model download until the catalog names a Hugging Face repository, revision, and filename. The checksum is the LFS oid read at download time. Do not hardcode that oid. Do not invent tokens per second or a device temperature.
- UI colors, type, and icons follow the ApexLedger Precision tokens: ink `#0F172A`, action `#1D4ED8`, paper `#F8FAFC`, body `#334155`, line `#64748B`, danger `#B91C1C`, success `#15803D`, violet `#6D28D9`. Minimum touch target 48dp. No emoji icons.
- No `any`, no error suppression comments, and no silent catch that stores secrets in plaintext.
