# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.1] - 2026-11-22

### Changed

- The README and KDoc put `FileSessionStateStore` and `FileSessionSink` in `context.noBackupFilesDir`:
  the state holds the install id and the open session, which must not come back on another device from
  the automatic backup.

### Fixed

- NaN and infinite property values are dropped. Kept, they made the next save of the state throw on
  the main thread.
- A store whose save throws (a full disk) no longer crashes the app from `tick` or `enterBackground`.
  The state stays unsaved and the next tick writes it again.

## [0.1.0] - 2026-11-21

### Added

- `SessionsSdk.NAME` and `SessionsSdk.VERSION` (in `sessions-core`). The version is sent as
  `sdk_version`, and a connector reports both when it sends batches.
- `sessions-core`: the light record. `AppSessions` splits sessions (launch, 30 minutes in the
  background), records screens with `via`, `back` and `quick_back`, taps (0–10000 of the window),
  rage taps, scroll depth, the keyboard, app events with kept props, marks, hangs and the end reason
  decided at the next launch. `SessionRules` passes the shared tables `cases/sessions` (split, rage,
  dead, quick back, end, scroll, tap position, sampling, kept props) with the same answers as
  swift-app-sessions. `SessionSink` with `FileSessionSink` and `DebugSessionSink`;
  `FileSessionStateStore`; `HangMonitor`; test doubles.
- `sessions-compose`: `SessionCapture` (taps through `Window.Callback`, front and back, the back key,
  `ApplicationExitInfo`, a crash handler before API 30, hangs), `SessionDeviceInfo.current`, and
  `Modifier.sessionTarget`, `sessionScroll`, `sessionMask`, `sessionNoCapture`.
