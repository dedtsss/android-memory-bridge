# Memory Bridge for Android

Memory Bridge is a small public Android client for an external Memory Gateway. It adds two Android selected-text actions:

- **В память / Save to memory** — sends the exact selected text and an optional note to the Gateway.
- **Вспомнить / Recall** — requests a ready-to-paste context-pack. In editable fields it returns the result through `ACTION_PROCESS_TEXT`; in read-only sources it offers Copy and Open ChatGPT.

It also receives `text/plain` through the Android Sharesheet. This repository is independent of Android Dispatcher and contains no memory backend.

## Build

Requirements: Android SDK Platform 36, Build Tools 35+, and JDK 17.

```bash
printf 'sdk.dir=/absolute/path/to/Android/Sdk\n' > local.properties
export JAVA_HOME=/absolute/path/to/jdk-17
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
```

The resulting APKs are at:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

`local.properties`, keystores, and signing configuration are ignored by Git. The package name is `net.brucegroup.memorybridge` (`.debug` suffix for debug).

## Configure and use

1. Open Memory Bridge and set the Gateway HTTPS URL and API token.
2. Optionally set `mock://memory-gateway` in a **debug** build for the deterministic in-app fake API; no token is required in that mode.
3. Select text in a compatible Android app, open the selection overflow menu, then choose **В память** or **Вспомнить**.
4. Or choose Memory Bridge from Share for a `text/plain` share.

The app never downloads content behind a shared URL. If a Share intent contains only a URL, it saves precisely that URL and clearly says that the full conversation was not received.

The Gateway contract is deliberately small and backend-neutral: [docs/protocol.md](docs/protocol.md). For the ChatGPT device check, use [docs/manual-test-chatgpt.md](docs/manual-test-chatgpt.md).

## Security model

The token is encrypted using an AES-GCM key generated in Android Keystore; only ciphertext and an IV are kept in private app preferences. Tokens, selected text, request bodies, and Gateway responses are not logged. Requests carry an opaque UUID `X-Request-Id` for diagnostics, while UI errors may show only that ID.

Release builds require HTTPS. Debug builds permit cleartext HTTP only for `localhost`, `127.0.0.1`, and the Android emulator host `10.0.2.2`. There is no accessibility service, overlay, clipboard listener, telemetry, background worker, local memory store, or ChatGPT modification.

## Signed releases

The repository creates unsigned release APKs only. To build a signed release locally or in CI, provide all of these environment variables:

`MEMORY_BRIDGE_SIGNING_STORE_FILE`, `MEMORY_BRIDGE_SIGNING_STORE_PASSWORD`, `MEMORY_BRIDGE_SIGNING_KEY_ALIAS`, and `MEMORY_BRIDGE_SIGNING_KEY_PASSWORD`.

For GitHub Actions, keep the base64 keystore and each of those values in GitHub Actions Secrets, decode the keystore to `$RUNNER_TEMP`, and pass its path as `MEMORY_BRIDGE_SIGNING_STORE_FILE`. Never commit a key.

## Scope and limitations

The Android platform offers `ACTION_PROCESS_TEXT`; whether a particular source application displays actions or accepts a replacement is controlled by that source and Android. This project does not claim verified integration with the official ChatGPT Android app until the manual device procedure has been run.

License: pending owner decision.
