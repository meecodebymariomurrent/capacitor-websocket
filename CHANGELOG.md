# Changelog

All notable changes to `@meecode/capacitor-websocket` are documented here.

## [1.0.3] - 2026-11-17

### Changed
- `connect()` now resolves once the socket is actually open and rejects if the connection fails or takes longer than 5 seconds (all platforms).
- `send()` rejects when the socket is not connected; unknown socket names are rejected with a clear error.
- Calling `build()` again with an existing name closes and replaces the previous socket; sockets are disposed when the plugin is destroyed (iOS).
- Events and payloads are now identical on Android, iOS and web (`connected`, `message`, `disconnected`, `statechange`, `closeframe`, `connecterror`, `error`, `senderror`). Binary frames arrive base64 encoded as `{ data, binary: true }`.
- `SendOptions.data` is now typed as `string` (was `any`); `MessageEvent` gains optional `binary`.
- Web implementation reworked for parity with the native platforms.
- Android implementation rewritten on top of OkHttp 5.
- README documents the connection lifecycle and event payloads.

### Deprecated
- `messageerror`, `textmessage` and `textmessageerror` events are no longer emitted.

### Fixed
- Android: Gradle error caused by `proguard-android.txt`; now uses `proguard-android-optimize.txt`.
- Android: upgraded the Gradle wrapper to 9.4.1 (works with Java 25).
- iOS: pods built with a deployment target of 12.0 failed on current Xcode; the Podfile now sets 15.0 for all pods.
- iOS: `'WebSocket' is ambiguous for type lookup` with the iOS 26+ SDK; Starscream's type is now fully qualified.

### Added
- Automated web tests (`npm test`), including a parity test against the native event contract, and expanded iOS unit tests.
