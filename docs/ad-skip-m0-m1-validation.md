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


## Controlled audio fixture

Run `python3 scripts/ad-skip-fixtures.py` to regenerate the local ignored artifacts under `artifacts/ad-skip/m1/fixtures/`. The generator uses only the Python standard library and writes `seek-fixture.wav`, `result.json` and `manifest.json` deterministically.

- Audio: 16 seconds, mono 16-bit PCM at 16 kHz, SHA-256 `4adbac1071b2afd84cd481d10e9afecf3593cbe5785a45c450a55ae99e6a9eed`.
- The marked intervals use a 1 kHz tone; other audio uses a 400 Hz tone. Expected merged intervals are [0, 1000), [2000, 4000), [8000, 10000) and [14000, 16000) milliseconds. The original ranges intentionally overlap or touch so the fixture exercises normalization while preserving gaps.
- `manifest.json` records 14 positions and their expected range-end targets, including each start/end boundary and the episode end.
- Regeneration in a separate temporary directory produced byte-identical artifacts. WAV format/duration, exact SHA-256, required result/range schema fields, normalization and all 14 expected targets were checked successfully.
- `result.json` is explicitly controlled fixture data with job ID `controlled-seek-fixture` and runtime identity `fixture: true`. It is not an AudioAdDetect response or detector-quality evidence. Device use must preserve the matching file hash/job association; do not substitute it for evidence of live native upload/admission/result retrieval.

The audible Samsung checks remain pending. No phone state or runtime observation has been inferred from the generated fixture.

## Progress review while USB and owner testing are unavailable

Reviewed on 11 October 2026 at `872a5af4a`; the owner has deferred USB access and owner testing for several hours. This review does not repeat builds/tests or claim native runtime acceptance.

- Both retained APKs exist and their recomputed SHA-256 values match the records above.
- Retained test XML totals were independently recounted: `:model` 94, `:net:common` 144, `:storage:database` 252, `:playback:service` 80 and `:net:download:service` 340 executions across variants; total 910, zero failures/errors.
- Temporary JDK `javac`, SDK 36 platform/build tools and the generated build directory are still present. No new toolchain provisioning is necessary while they remain available.
- Source review confirms that automatic download of missing queued audio is not implemented. Queue events/startup already trigger worker scans; this is partial M2 implementation, not a completed automatic queue workflow.
- Worker tests mock the coordinator/client, and seek tests use a mocked player. Scheduler, Keystore, real decoding/audio and UI execution need additional runtime evidence.
- `/dev/kvm` was absent in the review environment; the temporary SDK has no recorded emulator system image. Emulator work is an optional bounded attempt, not a prerequisite for continuing desktop implementation or a Samsung substitute.
- The generated fixture has no installed-app import/feed/API harness yet. Preparing a test-only delivery route is necessary before calling it a runtime fixture test; its JSON is not a live detector result.

Continue the [work queue without USB or owner review](ad-skip-implementation-plan.md#work-to-complete-without-usb-or-owner-review): finish automatic downloads, reproduce/fix source recovery findings, compile/test and retain a new candidate plus prepared phone checklist. Keep this historical M0/M1 evidence unchanged; append new candidate results only after execution. Samsung installation/native HTTPS, actual automatic/manual audio seeks, offline/restart, a real fresh episode and owner usability remain open.
