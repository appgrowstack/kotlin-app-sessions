# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- The recording. `SessionReplayRecorder` (pass it as `AppSessions(replay = …)`) builds segments from the
  frames and keeps them in `<directory>/sessions/replay/<session_id>/<seq>.agsr`: a segment closes at 60
  seconds, 900 KB of images or 600 frames; the folder keeps 50 MB and 7 days, a session 30 minutes or 20 MB.
  `AppSessions` sends them to its `SessionReplaySink` by the answer's mode: `RECORD` all, `BUFFER` the last
  60 seconds and what follows once a priority condition is met (or the session crashed, hung or was killed),
  `NONE` none. Unmetered networks only, a priority session's also metered up to 3 MB a day
  (`AppSessions.network`, `SessionNetwork`). `UPLOADED` and `REJECTED` remove the segment, `FORBIDDEN` the
  session's; a throw keeps it.
- `SessionReplaySegment` writes and reads the `.agsr` segment (and passes `cases/sessions/segment-*`).
  `SessionReplayFrame` (`Image`, `Same`, `Blank`).
- `SessionRules.maskRects(scale, image, rects)` (passes `cases/sessions/mask-rects-*`),
  `SessionRules.frameFingerprint(luma)` and `SessionRules.luma(argb)`.
- `AppSessions.replayImage`, `replayBlank`, `replayPaused`, `replayActive`, `replayStopped`,
  `replayFrameIntervalMs` and `uploadToken(sessionId)`. `isCapturing` is true while frames come, so dead taps
  are recorded (`dead`). Each batch carries `replay_stats` (`SessionEvent.ReplayStats`) when frames were taken
  or the recording stopped (`SessionReplayStopReason`).
- `SessionSinkReply.Accepted.uploadToken` (kept with the session across launches) and `live`
  (`SessionLiveOffer`). `SessionReplayMode.NONE` (stays to the end of the session) and `SessionMaskMode`.
- The live view: `AppSessions(liveTransport = …)` with `SessionLiveTransport`, `SessionLiveConnection` and
  `SessionLiveListener` (hello, events at once, frames up to 2 a second, `stop` and `rate`; closed in the
  back; a batch every 5 seconds while open). The package has no WebSocket of its own.
- `sessions-compose`: `SessionCapture.install(…, replayOptions = SessionReplayOptions(mask, noCaptureScreens))`
  takes the frames with `PixelCopy` (painted over before they are shrunk to 1 dp = 1 px, WebP quality 35) and
  sets `AndroidSessionNetwork`. The module declares `ACCESS_NETWORK_STATE`.
- Test doubles `RecordingSessionReplaySink` and `RecordingLiveTransport`.

### Changed

- `Modifier.sessionMask()` is painted in every frame of the recording.

## [0.2.0] - 2026-11-23

### Changed

- `Modifier.sessionNoCapture()` holds until its screen's lifecycle owner is destroyed, or resumed
  again after the mark left the composition, instead of ending when it leaves the composition. A
  screen pushed on a marked screen with Navigation Compose stays blank until the marked screen is
  popped. `AppSessions.beginNoCapture()`, `endNoCapture()` and `isCaptureSuppressed` are replaced by
  `holdNoCapture()` (`NoCaptureHold`) and `noCaptureHeld`.

### Added

- `SessionCapture.blankReason(window, sessions, noCaptureScreens)`: why a frame taken now would be
  blank, from `FLAG_SECURE`, the holds, the window without the focus (a dialog, a `ModalBottomSheet`,
  a focusable popup or the system over it) and the manifest's `no_capture_screens`.
- `SessionRules.blankReason(scene, noCaptureScreens)` with `SessionCaptureScene` and `SessionBlankReason`.

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
