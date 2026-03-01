# RemoteLogcat SDK

RemoteLogcat is a debug-only Android SDK that captures runtime logs, app breadcrumbs, and crash context, then lets QA open a report screen and share it.

The SDK is designed for fast triage:
- Top `SUMMARY` section with device health.
- Bottom `RAW CHRONOLOGICAL` section with timestamp-ordered events.

## 1. Install

### 1.1 Add JitPack repository

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 1.2 Add dependency (debug only)

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("com.github.rajeshray:remotelogcat-android:1.0.7")
}
```

## 2. Initialize

Initialize once in your `Application`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteLogcat.init(this)
    }
}
```

## 3. Android 13+ Notification Permission

Foreground notification requires runtime `POST_NOTIFICATIONS` permission on Android 13+.

- SDK still starts and captures logs in first session.
- Without permission, notification visibility/actions may be limited.
- Request permission in your app for full notification + report/share UX.

Example:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
}
```

## 4. Runtime Flow

1. SDK starts foreground service in debug build.
2. Notification appears with text like `Tap to see report`.
3. Tapping notification opens SDK report preview screen.
4. QA taps `Share` inside preview to open Android share sheet.

If notification permission is denied, open report manually from app/debug menu:

```kotlin
RemoteLogcat.openReport(context)
```

Crash flow:
1. App crashes.
2. SDK writes `crash_<timestamp>.txt` synchronously.
3. On relaunch, SDK detects pending crash report.
4. Notification points QA to view/share crash report.

## 5. Report Format

Every report contains exactly two sections.

### 5.1 SUMMARY (top)

High-signal health snapshot:
- session duration
- total raw event count
- memory usage
- CPU usage (system + app)
- battery + power saver
- network type + strength
- disk free
- screen metrics
- device + Android info
- app version/build
- crash class/message (crash reports)

### 5.2 RAW CHRONOLOGICAL (bottom)

Single timeline sorted by timestamp:
- logcat lines
- breadcrumbs
- crash + stack lines (crash reports)

This gives one place to read everything in sequence.

## 6. Breadcrumb API (recommended)

Use breadcrumbs for business-context events:

```kotlin
RemoteLogcat.breadcrumb("Checkout started", mapOf("cartId" to "C123"))
RemoteLogcat.breadcrumb("Payment failed", mapOf("httpCode" to "500"))
```

You can also open the report screen directly:

```kotlin
RemoteLogcat.openReport(this)
```

Tips:
- Add breadcrumbs at user actions, API start/end, and state transitions.
- Keep keys stable (`screen`, `flow`, `userType`, `requestId`).

## 7. Configuration Guide

```kotlin
RemoteLogcat.init(this) {
    maxLogLines = 1500
    anrThresholdMs = 5000
    notificationTitle = "QA Debug Session"
    notificationText = "Tap to see report"

    maxStoredReports = 20
    maxBreadcrumbs = 200
    reportWindowMinutes = 3
    includeSdkInternalLogs = false
    startupBackfillLines = 0
}
```

### What each setting does

- `maxLogLines`
  - Max in-memory log entries in circular buffer.
  - Oldest entries are dropped first.

- `anrThresholdMs`
  - Main-thread stall threshold for `possible ANR` warning logs.

- `notificationTitle` / `notificationText`
  - Text shown in persistent notification.

- `maxStoredReports`
  - Max saved report files in `cacheDir/remotelog/`.
  - When limit is exceeded, older files are pruned.

- `maxBreadcrumbs`
  - Max in-memory breadcrumbs.

- `reportWindowMinutes`
  - Include only recent, incident-scoped events in report.
  - Helps avoid stale/old log noise.

- `includeSdkInternalLogs`
  - `false`: hides SDK internal `[REMOTELOGCAT]...` lines.
  - `true`: includes them for SDK debugging.

- `startupBackfillLines`
  - Backfill count from logcat at startup (`-T`).
  - `0` disables backfill (recommended for cleaner reports).

## 8. Recommended Defaults

For most teams:

```kotlin
RemoteLogcat.init(this) {
    maxLogLines = 1500
    reportWindowMinutes = 3
    startupBackfillLines = 0
    includeSdkInternalLogs = false
    maxBreadcrumbs = 200
    maxStoredReports = 20
}
```

For startup-crash heavy apps:

```kotlin
startupBackfillLines = 100
```

Avoid large backfill values unless needed.

## 9. Storage & Retention

Reports are written to:

`context.cacheDir/remotelog/`

File names:
- `crash_<timestamp>.txt` for crash reports
- `report_<timestamp>.txt` for manual reports

Retention is controlled by `maxStoredReports`.

## 10. Permissions Used by SDK

SDK manifest provides:
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS`
- `ACCESS_NETWORK_STATE`

No host manifest edits required in typical setup.

## 11. Troubleshooting

### Notification not visible
- On Android 13+, ensure `POST_NOTIFICATIONS` is granted.
- Confirm app is running debug build.

### Report missing expected events
- Increase `reportWindowMinutes` (for example `5`).
- Temporarily set `includeSdkInternalLogs = true`.
- Add breadcrumbs at key user/app events.

### Too much noise in report
- Keep `startupBackfillLines = 0`.
- Keep `includeSdkInternalLogs = false`.
- Reduce `reportWindowMinutes`.

### Network strength shows `Unknown`
- Some devices/OS versions do not expose reliable signal strength.
- SDK falls back safely without breaking report.

## 12. Scope

- Debug-only usage is strongly recommended (`debugImplementation`).
- No backend, no cloud, no upload service.
- Sharing uses standard Android share sheet from preview screen.
