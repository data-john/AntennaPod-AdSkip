# M0 and M1 delivery record

Date: 11 October 2026. Branch: `codex/ad-skip-m0-m1`.

## M0 baseline

- Upstream source: `9c7ffa16736c020045571d274aef4cdcf0878100`, version 3.12.3.
- No nested AGENTS.md files found. Application source and version unchanged for the baseline.
- Existing system Java 21 was runtime-only. Provisioned Temurin JDK 21.0.12.1 at `/tmp/antennapod-jdk21`.
- Provisioned SDK platform 36 and build-tools 36.0.0 at `/tmp/antennapod-sdk` using Google's command-line tools. Download SHA-256: `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.
- Home filesystem NAME_MAX is 143; D8 generates longer filenames. Generated `app/build` is linked to `/tmp/antennapod-app-build` (NAME_MAX 255), with no source/build-version changes.
- `./gradlew :app:assembleDebug`: successful after toolchain and build-directory setup. Existing warnings remain unchanged.
- Baseline Play debug APK: `artifacts/ad-skip/m0/antennapod-baseline-play-debug.apk` (ignored by Git), SHA-256 `ea572889756257a6f1fe52e461c8ef71acfc0be435ba9fd7d177c5a6f28837df`.
- Set JAVA_HOME to `/tmp/antennapod-jdk21`, ANDROID_HOME to `/tmp/antennapod-sdk`, and prepend `$JAVA_HOME/bin` to PATH before the mandated build/install commands. Temporary toolchain/build directories need reprovisioning if removed.
- ADB server starts with authorised host access. Initially no connected device or Samsung USB device was detected. The owner enabled USB debugging, but no trust popup or USB device has appeared.
- Samsung install/model/Android, baseline smoke checks, pilot data preservation and authenticated native HTTPS connectivity are pending. M0 exit is not yet achieved.

## M1 integration

Implemented an opt-in integration for already-downloaded queued audio:

- Playback settings provide an HTTPS origin, a nonpersistent bearer-token entry encrypted with Android Keystore in no-backup storage, separate Auto-skip and mobile-data toggles. All default to off except the configured pilot origin.
- Raw exact-file uploads use SHA-256 and a submission key persisted before admission. Job IDs and original validated result JSON survive process restart. Dedicated transport refuses redirects and bounds each request below the gateway timeout.
- Durable constrained work advances one analysis at a time, recovers a lost admission response with the same key, polls admitted jobs, and exposes retry/cancel and bounded outage recovery. Automatic downloads of missing queued episodes remain M2 work.
- Result parsing checks schema, job, hash, duration tolerance and range geometry, preserves provenance, merges overlapping/touching ranges and distinguishes valid empty/degraded outcomes.
- The active Media3 service loads and hashes cached results on a background thread, checks ranges at 250 ms while playing, seeks to the range end, and exposes manual Skip ad. Paused automatic playback, streaming, Cast and stale episode identities are excluded. File observation clears/revalidates replaced audio. No server access is needed for cached playback.

Integration source commit: `1d44f8e28` (foundation: `c8b479850`).

Validation:

- Integrated Play debug APK: `artifacts/ad-skip/m1/antennapod-ad-skip-play-debug.apk` (local ignored artifact), SHA-256 `c04bb264edea4fdfc064f09d587d9d4f746692bef2287a0f5878103aa942ee53`.

- `./gradlew :app:assembleDebug` succeeds for Free and Play debug builds with the existing toolchain. Full output is retained locally with the APK.
- `./gradlew --console=plain :model:test :net:common:test :storage:database:test :playback:service:test :net:download:service:test` passed. Focused worker/playback additions subsequently passed `./gradlew --console=plain :net:download:service:test :playback:service:test`.
- After the final build, `./gradlew --console=plain :model:test :playback:service:test :net:download:service:test` passed again.
- Latest XML reports contain 910 test executions across build variants, zero failures/errors. This includes 20 new test methods covering transport identity/redirects, parser failures and empty/degraded results, range boundaries, persisted admission/job recovery, concurrent cancellation and late results, bounded outages, offline cached ranges, same-size/same-timestamp audio replacement, automatic/manual seeks, pause/off behavior, Cast/streaming/stale identities and repeated-seek suppression.
- New test fixture setup errors were fixed in the new tests; existing application tests/code were left unchanged.

Samsung deployment, native authenticated HTTPS/upload, audible automatic/manual seeking and offline/restart acceptance remain pending. ADB still reports no connected devices after the owner enabled USB debugging. Neither M0 nor M1 exit is claimed yet.

## Pilot setup and remaining acceptance

1. Connect/unlock the Samsung using a data-capable USB cable, select Transferring files / Android Auto, and accept the USB debugging trust prompt when it appears. Record the device model and Android version once ADB reports `device`.
2. Install/start the baseline with the mandated Gradle/ADB command, smoke check subscriptions/downloads/queue/playback, and preserve pilot data before the integrated update. The release package must remain installed.
3. Install/start the integrated debug update with the same signing/package identity. In Settings → Playback → Ad skipping, enter the HTTPS origin and token locally, enable the integration, and keep Auto-skip off initially. Do not put the token in test notes or logs.
4. Queue an already-downloaded audio episode within the API limits (256 MiB, one hour), then use the player’s Ad analysis status action to observe upload/job/result progress. After repairing credentials/connectivity, use Retry to resume the saved job. Changing origin/configuration pauses old work rather than silently uploading to a different service.
5. Use exact-file non-empty controlled ranges to check manual Skip ad and Auto-skip, including paused/resumed playback, seeking into a range and the episode end. Then disable PC/Tailscale connectivity and force-stop/restart the app to demonstrate cached offline replay.
6. Record actual device/API/seek/offline observations here before closing M0/M1. Fixture success does not establish real-podcast detector quality or complete M3 acceptance.
