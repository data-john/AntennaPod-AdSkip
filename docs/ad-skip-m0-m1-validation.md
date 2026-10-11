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

## Independent candidate delivery — 11 October 2026

The newly requested interim objective is a tested candidate ready for installation, with Samsung and owner acceptance left open. The goal API refused creation because the earlier M0/M1 goal is still unfinished/blocked; it has not been falsely marked complete. Development proceeded under the updated A–D authorization.

Implemented and committed:

- `a87104b4c`: automatic reconciliation of all queued missing audio through AntennaPod's existing download service, off the UI/DB event thread. Requests retain Wi-Fi constraints even when AntennaPod permits mobile downloads; metered initiation requires both permissions. Durable waiting/network/failure state and suppression prevent ordinary duplicate/sort/move/startup events from restarting cancelled downloads. Explicit Retry or queue addition/replacement resumes them. Completed audio enters exact-file analysis once; partial audio remains ineligible.
- Remote job identity now survives deleted/replaced/orphaned media until a terminal response is known. Small polling/cancellation work requires connected networking, independently of full-audio permission. Lost/stopped admission uses the original key; cancellation of uncertain admission first recovers that job. Missing/changed original bytes leave a visible recovery block rather than creating another job. Explicit Retry can recover after restoring matching original bytes.
- Corrupt records become visible invalid state and stop automatic admission until explicit Retry. That Retry is a deliberate fresh submission because the original identity is unreadable; remote work should first be reconciled. Storage errors without a selected record do not create an immediate rescheduling loop.
- Scheduler requests are coalesced on a serial background executor using the actual WorkManager database. Control work remains CONNECTED across policy changes, avoiding an old UNMETERED head. Integration-initiated downloads carry their stricter constraints/tag; preference changes cancel tagged requests and stop an active upload. Connectivity callbacks stop uploads when full-audio permission is lost. Persisted per-record backoff remains bounded (six transport failures; server Retry-After capped at one hour); scheduler wakes are capped at 60 seconds. Processing polls grow from 10 to 60 seconds without counting processing as a transport failure.
- `c7b823740`: required metadata validation also recovers incomplete JSON rows. A translated status explains the deliberate fresh Retry when job identity is unreadable.
- `db3107512`: isolated HTTPS feed/API fixture harness, kept outside the production APK.
- `6fff00fdc`: target media events and unchanged verified playback caches avoid unnecessary clearing/rehashing. Auto-skip setting changes retain verified ranges; disabling integration still clears eligibility. File observation continues to force verification on modification.

Validation actually executed:

- `./gradlew :app:assembleDebug` succeeded after the changes; final build took 15 seconds and produced Free and Play debug APKs. Complete final compiler output was retained and inspected, including paged retrieval where the tool display truncated it. Existing warnings were left alone.
- The five integration modules passed `./gradlew --console=plain :model:test :net:common:test :storage:database:test :playback:service:test :net:download:service:test`. Final changed-module coverage then passed `./gradlew --console=plain :storage:database:test :playback:service:test :net:download:service:test` in 1m31s. After the final incomplete-record fix, `./gradlew --console=plain :storage:database:test :net:download:service:test` passed in 1m7s.
- Latest retained XML: model 94, net/common 144, storage/database 252, playback/service 84, net/download/service 416; **990 executions, zero failures/errors** across variants. Twenty additional test methods cover download deduplication/bulk queues/network combinations/cancellation suppression, an actual offline WorkManager scheduler, completion caching, late deletion, uncertain and stopped admission, remote cancellation without audio permission, corrupt-record recovery and playback-cache reload avoidance. Client/player/database mocks remain in worker/seek tests; real device execution is not inferred from them.
- Candidate APK: `artifacts/ad-skip/candidate/antennapod-ad-skip-candidate-play-debug.apk`; SHA-256 `b70ca7a9897142c9d0520f94b6380872fc6e4ec3229231213572a399a901e6b1`. Application source content corresponds to `c7b823740924f0b700a154a838a920aa886864db`; the build ran before those changes were committed. No app source remained uncommitted afterward. The retained source patch SHA-256 is `c5b66a269ac32b843e11090293eafa2fa96e7d896f4a1324236438dba16f4f88`.
- Baseline/candidate package and version metadata match: `de.danoeh.antennapod.debug`, 3120395 / 3.12.3. `apksigner verify --print-certs` succeeded for both; shared certificate SHA-256 `4aa8d89b5e7491eb5828b6a633837b34a467b21b1668fd6e2e2038f1103f1214`. Full signing outputs, manifest, complete build/test logs, source patch and XML reports are retained beside the candidate; M0/M1 artifacts remain intact.
- No preference/token implementation changed, so the separate preferences suite was not rerun. No PR was opened; repository-wide checkstyle/lint was not required for this candidate handover.

A separate test-only HTTPS harness now serves the deterministic feed/audio and raw-file admission/poll/result/cancel/retry routes. It rejects mismatching bytes and preserves controlled fixture provenance. Declared result/status fields were checked against the retained backend `release/openapi-v1.json`. Desktop HTTPS checks passed for feed/audio, authentication, exact-byte hash, stable-key admission, queued/running/completed result, cancellation winning/losing completion and retry. An early unauthorized-response socket race found on a repeat was fixed in the harness before the retained successful check. The test client explicitly trusted a temporary localhost certificate; production app TLS was unchanged. No credentials, certificate private keys or test token were committed. The harness needs a phone-trusted hostname/certificate and private listener when deployed; it is not a running phone endpoint or live detector evidence.

Optional emulator setup was skipped after confirming no emulator executable/system image or `/dev/kvm` in the available environment; no virtualization or service settings were changed. No authenticated host/live backend inference was performed. Samsung scheduling, Keystore/UI, decoding/audio, private-network connectivity, offline/restart and fresh real-episode/owner acceptance remain pending. Broader stopped/network/configuration transition races still need native runtime qualification.

The [phone acceptance checklist](ad-skip-phone-checklist.md) provides installation, fixture access, recovery, coexistence and rollback steps. The achievable interim candidate is delivered; physical M0–M3/R12 exits are not claimed.


## Samsung installation checkpoint — 11 October 2026

The Samsung SM-A266B is now connected through authorised USB ADB and reports Android 16. Initially only `de.danoeh.antennapod` was installed. The unchanged source at `a9078deb5` compiled with the mandated assemble command (40 seconds), then installed and launched with the mandated Gradle/monkey command (18 seconds). Both release and Debug packages coexist.

The Debug baseline subscribed through its normal RSS dialog to the generated controlled tone feed served on USB loopback. Its normal Download action fetched the 512,044-byte WAV, added it to the queue and decoded a 16,000 ms duration. Native Media3 session inspection showed PLAYING at position 9,226 ms, buffered through 16,000 ms, speed 1.0 and a one-item queue. This is observed native playback state; owner audible confirmation remains pending. After playback completed, a private mode-0600 archive of Debug databases/files/preferences was retained before updating. No release data was read or modified.

Returned to `codex/ad-skip-m0-m1` at `a590605fa`. The integrated source compiled successfully (21 seconds). An initial installation attempt failed because USB disconnected briefly; ADB recovered and the required installation command succeeded on retry (17 seconds). The subscription, completed-playback state and downloaded fixture survived the update. Playback settings display the configured private HTTPS origin and local encrypted-token entry; integration, Auto-skip and mobile analysis remain off.

The exact rebuilt phone APK is retained separately at `artifacts/ad-skip/phone/antennapod-phone-play-debug.apk`, SHA-256 `a4e3b4642519a731ab6f17873cb320885e5e6f6da6f52fbc86fe63f8d27fa4d1`. Its source matches the candidate application source; rebuilding after the baseline checkout produced a different APK archive hash. The previously retained candidate is unchanged. Full build/install logs and the pre-update private Debug-data archive are retained under `artifacts/ad-skip/phone/` (ignored by Git). No application source changed during installation, so prior module-test evidence remains applicable.

M0 baseline installation and subscription/download/queue/playback-state checks now have device evidence. M0 authenticated native HTTPS access, M1 native upload/result and automatic/manual skipping, audible owner observations and offline/restart acceptance remain open. The owner has been asked to connect phone Tailscale and enter the existing bearer token locally, keeping it out of chat and logs. The temporary unauthenticated USB feed exercises ordinary download/playback only; it is not evidence of authenticated analysis transport.


## Samsung native transport and controlled seeks — 11 October 2026

The owner connected Tailscale and saved the existing bearer token locally. Integration was enabled through Playback settings with Auto-skip initially off and mobile analysis off. Queueing the exact downloaded tone fixture automatically admitted `analysis-e91c8adf94544fd1a84d728cd62cd38e`; native saved metadata showed matching SHA-256 `4adbac1071b2afd84cd481d10e9afecf3593cbe5785a45c450a55ae99e6a9eed`, 512,044 bytes, 16,000 ms, stable submission key, RUNNING/source and bounded polling. The server then reported FAILED; its retained failure report says `child RuntimeError: source stage failed: inference`. The original native record is retained as `artifacts/ad-skip/phone/native-tone-analysis.json`. This proves authenticated native HTTPS upload/admission/status, not a successful detector result. No automatic inference retry was requested.

After preserving that terminal record, only the dedicated Debug tone-fixture record was seeded with the generator's explicitly labelled synthetic result (`runtime_identity.fixture=true`, `controlled_fixture` provenance, zero inference calls). This is a controlled cache setup, not a result returned by the live API. The original downloaded bytes were independently hashed on the phone and matched the fixture manifest. With Auto-skip off, manual Skip ad while paused moved 813 → 1,000 ms and 2,464 → 4,000 ms, preserving PAUSED. At 5,952 ms the menu had no Skip ad action. Auto-skip was then enabled through settings; seeking into the marked interval while paused left position at 2,464 ms until resume. AntennaPod's existing pause-rewind behaviour applies on resume.

At 1×, native media-session sampling recorded automatic targets 1,000 and 4,000 ms. The UI speed control was set to 2.00; playback recorded a 10,000 ms seek and completed through the normal empty-queue state. Samples record media-session snapshots, which can retain the last published position between updates; they are not a measurement of exact audible skip latency or every millisecond boundary. Full samples are retained in `controlled-auto-1x.json` and `controlled-auto-2x.json` under the ignored phone artifact directory.

Wi-Fi and mobile data were both temporarily disabled (both global settings read 0), Debug was force-stopped/restarted, and the exact downloaded fixture replayed at 2×. Native snapshots showed buffering/playing at the 1,000, 4,000 and 10,000 ms targets, followed by normal completion. Samples are retained in `controlled-auto-offline-restart.json`. Both original network settings (1/1) were restored. The owner answered yes to audible tones and clean skipping. This qualifies Samsung controlled manual/automatic seeking and cached offline restart; Bluetooth, screen-off, the complete 14-boundary matrix and fresh real-episode acceptance remain pending.

M0 now has baseline install/playback and authenticated native API evidence. M1 seeking/offline checks have controlled device evidence, but a successful native result fetch and one successful native full pipeline remain to be verified. The detector-side failure is separate from the passing native transport and synthetic cached-playback observations.


## M0/M1 device integration exit — 11 October 2026

Native replay of retained completed job `analysis-952dd26902ad481cb7bec42085b9a481` exposed a cancellation/completion race: the API returns HTTP 409 when cancellation reaches a terminal job, and the worker previously stopped as INVALID without retrieving the result. Source fix `987955223` checks the known job after that conflict, preserves terminal cancellation responses and fetches/validates a completed result when the local file still matches. A conflict whose status remains running still stops as INVALID. Three regression methods cover those cases; `./gradlew :app:assembleDebug` passed in 6 seconds with the complete unfiltered output inspected, followed by `./gradlew --console=plain :net:download:service:test` passing in 46 seconds. XML reports contain 428 executions (107 per variant), zero failures/errors/skips. Together with the unchanged previously retained model/net-common/database/playback reports, the candidate evidence covers 1,002 executions, not 1,002 distinct methods or an all-module rerun. Existing Gradle/JUnit ignored-base-class warnings remain unchanged.

The required install/start command passed in 9 seconds on SM-A266B / Android 16. The exact installed APK is `artifacts/ad-skip/phone/cancel-recovery/antennapod-recovery-play-debug.apk`, SHA-256 `e09d12b9884e177b3ef83d8f0a6f424efed8b7de786c1e369bc2e9ddcdad3f35`, app source `987955223`. Signing verification passed with the same baseline certificate. Full build/test/install logs and XML reports are retained in that ignored directory. Native replay then reached EMPTY / server COMPLETED and saved the API's `valid_empty` result, matching job ID, hash `b2cab62d36ca0a4ed9dc82728293610f85fd88a521167c40e57aba4012bb50c6` and 14,987 ms duration. That replay used a deliberately seeded known job association and exact retained bytes, with no new upload/inference; it is recovery evidence. The original tone bytes and explicitly labelled synthetic cache were restored afterward.

A separate RSS subscription, **AdSkip owned programme check**, supplied the existing owner-qualified 15-second programme WAV as an undownloaded episode. The only episode action was **Add to queue**. Automatic reconciliation requested and completed the 660,954-byte download; the existing bounded download-wait continuation ran after about 60 seconds, without a manual Download/Analyse/Retry action. Native raw upload admitted fresh job `analysis-4015eb56a66843b7b9e8dc25d52654ed` with submission key `b6e32d80-fb63-451f-9a1b-5c69d79b9474`, the same exact-file hash and 14,987 ms duration. Polling progressed through RUNNING/source to COMPLETED. The app fetched and validated the result, saved EMPTY / `valid_empty`, with zero ranges, five content labels and two attempted/two valid classifier calls under `promotion-portable-host-v7`. This is a new native admission/result pipeline, distinct from replaying the old completed job; it is a short owned programme control, not a fresh full podcast or non-empty live ad result. Its normal playback completed without skips.

With Wi-Fi and mobile data both 0, force-stop/restart retained that exact fresh job/result as EMPTY; both original network settings were restored to 1/1. Native result/progress/restart records are retained under the recovery artifact directory. The earlier controlled non-empty cache tests provide service-side manual/automatic seek and offline audible evidence; the owner confirmed tones and clean skipping. Integration remains enabled with the owner-entered encrypted token; Auto-skip is restored off, mobile analysis off and playback speed 1.00. The programme is left paused. No release-package data or backend service configuration changed.

M0 and M1 now meet their stated integration exit criteria: Samsung baseline/update, authenticated native transport, exact identity, persistent API results, service-side seeking and offline restart. The original tone analysis still has its separately retained inference failure. M2 has implemented/tested queue downloads and one native automatic download, but its broader network/cancellation/recovery matrix remains open. M3/R12 fresh full-podcast non-empty result/skipping, all 14 boundaries, screen-off/Bluetooth, mobile permission transitions and final owner normal-use acceptance remain open. Completion of M0/M1 does not claim the full usable-podcast objective.
