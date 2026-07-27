# mandosteps

A minimal Android companion app that pushes my daily step count to
[anthonyta.dev](https://anthonyta.dev). Samsung Health writes steps into Android
Health Connect on-device; this app reads yesterday's and today's totals every
few hours (WorkManager) and POSTs them to the hub's ingest endpoint. Yesterday
rides along on every sync so a day where the phone was off heals itself.

No analytics, no third-party services, nothing read beyond the step totals.

## Build

Needs the self-contained toolchain in `../toolchains` (portable JDK 17, Gradle
8.11.1, Android SDK 35 — no system install). The ingest bearer is baked in at
build time and must be provided as an env var; the script refuses to run
without it:

```sh
STEPS_INGEST_SECRET=<bearer> ./build.sh
```

Output lands at `dist/mandosteps.apk` — sideload it, grant the Health Connect
steps permission, allow background sync. Done.
