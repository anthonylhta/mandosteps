# mandosteps

A tiny Android app that pushes my daily step count to my personal hub,
[anthonyta.dev](https://anthonyta.dev).

**Why this exists:** there is no sanctioned way to get steps out of a Samsung
phone from the cloud. Samsung Health has no public API, Google Fit's REST API
was shut down, and Health Connect is deliberately on-device only. So the number
has to be _pushed off the phone_ — and after an evening losing to a third-party
automation app's plugin chain, ~150 lines of Kotlin I can actually debug turned
out to be the cleaner path.

## How it works

```
Samsung Health ──sync──▶ Health Connect (on-device)
                              │  AggregateRequest (steps, local-midnight ranges)
                              ▼
                        this app (WorkManager, every ~6h)
                              │  POST {steps, date} · bearer auth
                              ▼
                   anthonyta.dev/api/daily/steps
```

One screen: a status line saying what the last sync did, a **sync now** button,
and a battery-exemption button so Samsung doesn't quietly kill the background
job.

Design notes, small app or not:

- **Missed days self-heal.** Every sync posts _yesterday and today_. A day the
  phone was off gets its final count backfilled on the next run — the server
  overwrites same-day entries, so re-posting is idempotent.
- **The bearer token never touches the repo.** It's injected into `BuildConfig`
  at build time from an environment variable; the build refuses to run without
  one. Git history stays clean by construction, not by care.
- **Fails visibly, not silently.** Network/read failures retry ×3 with backoff,
  then write a human-readable reason to the status line. Debugging is "open the
  app and read one line".
- Jobs persist across reboots (WorkManager); counts are clamped to the server's
  sanity ceiling; nothing beyond the two daily step totals is ever read.

## Building

```sh
STEPS_INGEST_SECRET=<bearer> ./build.sh
```

`build.sh` expects a self-contained toolchain (portable JDK 17, Gradle 8.11.1,
Android SDK 36) in `../toolchains` — no system install, no Android Studio. With
a normal Android setup, `gradle assembleDebug` works the same; the APK lands in
`dist/mandosteps.apk`, debug-signed for sideloading.

This is a personal, single-user app: the endpoint is my hub and the server side
lives in [anthonyta](https://github.com/anthonylhta/anthonyta). If it's useful
to you, fork it and point `INGEST_URL` at your own receiver.
