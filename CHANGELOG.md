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
- iOS: the handshake now sends the bare hostname in `Host` for default ports (as Android and browsers do) instead of `host:443`, which some gateways rejected with HTTP 403. A `Host` passed in `headers` is respected.
- iOS: a rejected handshake now reports the HTTP status and response headers in the `connecterror` event and the `connect()` rejection.
- iOS: `'WebSocket' is ambiguous for type lookup` with the iOS 26+ SDK; Starscream's type is now fully qualified.

### Added
- Automated web tests (`npm test`): lifecycle, edge cases (late events from closing sockets, timeouts, reconnects) and a contract test that every emitted event is documented with the documented payload keys.
- Android unit tests (`./gradlew test`) for the connection state machine, which moved out of the plugin class into `SocketSession` so it can be tested on the JVM.
- iOS unit tests for the connection state machine and for the plugin's argument validation and build/replace behaviour.
