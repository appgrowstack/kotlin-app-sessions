# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `AppSessions.noteDismissByApp()`: the app closes the current screen itself (a sheet that closes once a long
  press is done); the move back within 1 second is a `back` but not a `quick_back`.
- `foreground_ms` on `background` (`SessionEvent.Background.foregroundMs`) and `end`
  (`SessionEvent.End.foregroundMs`): the time the session spent in the front.
  `OpenSessionRecord.foregroundMs`, `foregroundSince` and `foregroundMsAt(t)`, `ClosedSessionRecord.foregroundMs`.

### Changed

- A session that stayed in the background for 30 minutes ends as soon as anything is recorded in the background
  after that (an event of a job), at the time it went to the background. Before, the event joined the session and
  stretched it.
- A screen named while no session is open is the first screen of the next session (`LAUNCH`), so a session that
  starts after a process without a screen, or after 30 minutes in the background, has its screen.
- `start.first` is documented as the first session this SDK records on the install, not the app's first launch.

## [0.4.0] - 2026-11-24

### Added

- `SessionMaskNode` and `SessionRules.partsToHide(root, limit)`: the search for the parts of a frame to
  hide, apart from Android. A `Modifier.sessionMask()`, a password field, or a surface or texture view is
  painted whole; a part whose place cannot be told, or a hierarchy of more than 10,000 nodes, drops the frame
  (`MASKING_FAILED`).
- `SessionRules.maskRects(scale, image, budgetMs, nowMs, find)`: the search fails closed. When it throws or
  takes over 16 ms (`SESSION_MASK_BUDGET_MS`), the frame is blank (`MASKING_FAILED`).
- `SessionRules.isPasswordInputType(inputType)` and `SessionRules.isPasswordHint(hint)`.
- `SessionReplayFrameCost.Verdict.VERY_SLOW` (one frame every 5 seconds), `verySlowP95Ms` (33 ms) and
  `percentiles` (the p50 and p95 of the frames counted in the window).
- `AppSessions.closePrevious(exit)`: ends the previous session and starts none; the next `enterForeground()`
  starts the session.
- `SessionProcessExit`, `SessionExitInfo.exits`, `currentPid` and `currentProcessName`, and
  `SessionRules.previousExit(exits, sessionStartedAtMs, currentPid, currentProcessName)`: the exit record of
  the previous session's own process.

### Changed

- Password fields also include an `EditText` whose `autofillHints` hold `password` or `newPassword`, and a
  Compose node whose content type is `ContentType.Password` or `ContentType.NewPassword`.
- `SessionRules.maskRects` drops the frame for a rectangle or a scale that is not a finite number, instead of
  painting nothing for it.
- Frames that hold the main thread no longer stop the recording at 33 ms (p95). Over 33 ms a frame is taken
  every 5 seconds; the session's recording stops (`DEGRADED`) only over 100 ms. Same rule as
  swift-app-sessions.
- `SessionCapture.install` no longer starts a session in `Application.onCreate`: it ends the previous one
  (`closePrevious`), and the session starts when the first activity comes to the front.
- `SessionCapture.exitInfo` reads the last 8 exit records instead of 1.

### Fixed

- A process started without a screen (a notification, a widget, WorkManager, an alarm) left an empty session
  (0 seconds, no screen) that the next launch ended as `killed` or `crashed`. Seen on users' devices and on
  Play's pre-launch robots. Such a process now records nothing.
- The end of the previous session could come from an exit record of another process: the newest record was
  used whatever its time. Only a record that ended at or after the session started, of another process than
  the current one (and of the same process name), counts; of those, the first to end.

## [0.3.1] - 2026-11-23

### Fixed

- The recording could stop for the rest of the session shortly after launch, on an emulator above all: the
  frames' main-thread time went over 33 ms (p95) while the app was still laying out its first screens, and
  `DEGRADED` is kept for the session. The first 3 frames of a session are no longer counted, and on an
  emulator (`Build.FINGERPRINT`, `Build.HARDWARE` and the like) a slow frame only spaces frames out. On a
  device, 33 ms still stops the session's recording. Same rule as swift-app-sessions 0.3.1.

### Added

- `SessionReplayFrameCost` holds the rule: over 16 ms (p95) one frame every 2 seconds, over `stopP95Ms`
  (33 ms; null never stops) the session's recording stops; the first 3 frames are not counted, the last 30
  are read once 10 are counted.
- `AppSessions.replayStopsOnSlowFrames` (true by default). `sessions-compose` sets it false on an emulator.

### Changed

- `sessions-compose` no longer plans a shot on every draw while no frames are wanted (the power check still
  runs, so a pause can end), and a touch or a drag left open when the app went to the back no longer holds
  frames back after it comes to the front.

## [0.3.0] - 2026-11-23

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
  (`SessionLiveOffer`). `SessionReplayMode.NONE` (stays to the end of the session) and `SessionMaskMode`
  with `MARKED` only: the recording paints only `Modifier.sessionMask()` and password fields; everything
  else is shown.
- The live view: `AppSessions(liveTransport = …)` with `SessionLiveTransport`, `SessionLiveConnection` and
  `SessionLiveListener` (hello, events at once, frames up to 2 a second, `stop` and `rate`; closed in the
  back; a batch every 5 seconds while open). The package has no WebSocket of its own.
- `sessions-compose`: `SessionCapture.install(…, replayOptions = SessionReplayOptions(mask, noCaptureScreens))`
  takes the frames with `PixelCopy` (`Modifier.sessionMask()`, password fields, surface and texture views
  painted over before they are shrunk to 1 dp = 1 px, WebP quality 35; `mask` defaults to `MARKED`) and
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
