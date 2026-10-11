# AntennaPod AdSkip MVP implementation plan

Date: 11 October 2026. Status: inspected and planned; implementation not started.

## Delivery approach

Implement the [objective](ad-skip-objective.md) as small additions to the existing Java app and modules. First put an unchanged baseline on the Samsung phone, then deliver one native end-to-end episode flow, then complete automatic queue/download coverage and restart/offline reliability. Keep an installable APK at each milestone. No new backend, framework, large database migration or player replacement is needed for the first build.

Planning allowance: approximately 4–6 focused development days after build/device access is working and the API accepts the chosen episodes. Aim for the first integrated phone build within 1–2 days of that baseline. These are effort estimates, not calendar commitments; backend full-podcast qualification and local toolchain/device access can extend delivery. Ship the small complete MVP before adding any deferred features.

## Inspected starting point

| Area | Finding | Consequence |
| --- | --- | --- |
| Repository | Clean `develop` checkout at `9c7ffa16736c020045571d274aef4cdcf0878100`; app version 3.12.3. | Record this baseline and create a `codex/` feature branch before committing implementation. |
| Build | `common.gradle`: Java 21, compile/target SDK 36, minimum SDK 23. Wrapper: Gradle 9.3.1; catalog: AGP 9.0.1. | Use the existing build configuration. Default shell Java is 17; Java 21 is installed and must be selected. Verify SDK 36/build tools availability rather than changing target versions. |
| Queue | `DBWriter.addQueueItem` and `addQueueItemAt` emit `QueueEvent`; automatic queue sorting replaces ADDED events with SORTED. | A listener for ADDED alone misses eligible episodes. Reconcile queue membership for ADDED, SORTED and SET_QUEUE, with startup reconciliation. |
| Downloads | `DownloadServiceInterfaceImpl` schedules existing download workers and connectivity constraints. `MediaDownloadedHandler` writes completed media before `FeedItemEvent`. | Reuse downloads; trigger analysis only after the completed local file and database state are available. |
| Playback | `USE_MEDIA3_PLAYBACK_SERVICE` is true. `Media3PlaybackService` observes position on the main thread and already handles intro/ending skips and seek statistics. | Integrate the active Media3 service. Changing only the legacy `PlaybackService` would not deliver this feature. |
| Libraries | OkHttp, WorkManager, EventBus and Java/Robolectric test infrastructure already exist. | Reuse them without adding a parallel HTTP stack or architecture. |
| Device access | `adb` is installed; its server could not start in this restricted planning session because the local listener was denied. | Phone connection/model/Android version remain unverified. Resolve execution access in implementation before claiming installation. |
| Backend | Current sibling pilot documents record live private HTTPS and completed browser qualification. | Do not redo PC provisioning. Native app connectivity and fresh episode processing still need evidence. |

The referenced pilot plan was found in the sibling AudioAdDetect checkout, rather than as a tracked document in this fork. The current onboarding supersedes stale caveats in `release/consumer-api.md` about unbounded uploads: the deployed v6 gateway documents a 120-second total deadline. Reconfirm the installed service contract at implementation time; do not infer live availability from a file.

## Minimal design and change locations

| Responsibility | Proposed location | Focused change |
| --- | --- | --- |
| Result/status/range objects | `:model`, small ad-skip package | Millisecond range data, media identity and typed states; no changes to existing episode semantics. |
| API transport | `:net:common`, dedicated AudioAdDetect client | Raw-file upload, admission, status/result retrieval, explicit retry/cancel. Isolate bearer credentials from podcast-feed requests. |
| Persistent job/result storage | `:storage:database`, separate ad-skip store class | Atomic versioned files under internal `filesDir/ad-skip`, keyed by media ID with hash identity. Avoid altering AntennaPod's main database schema for the MVP. |
| Settings and token | `:storage:preferences`; settings UI in existing app/preferences resources | Enable integration, HTTPS address, locally entered protected token, Auto-skip and mobile-data policy. |
| Queue/download orchestration | `:net:download:service`, coordinator and workers | Listen to existing queue/item events, reconcile from DB, request downloads through the existing interface, deduplicate and schedule upload/poll work. |
| Application initialisation | `ClientConfigurator` | Initialise the coordinator once with application context; register event listeners and reconcile outstanding work. |
| Status change notification | `:event`, one ad-skip update event | Refresh player/status and load newly available ranges without repeated disk reads in playback ticks. |
| Automatic/manual seeks | `Media3PlaybackService`; existing media-session callback/controller path | Share one range lookup/seek policy for automatic skipping and a minimal manual Skip ad action. |
| Player status/action | Existing `AudioPlayerFragment` and its view/menu/controller | A compact status and Skip ad control; Retry/cancel can live in a small analysis status dialog. |
| Translations | `ui/i18n/src/main/res/values/strings.xml` | All new visible strings belong here; do not change translated files. |

Keep the coordinator out of `DBWriter` so the database layer does not gain a dependency on network workers. The proposed modules already provide the essential dependency direction: download workers can access storage/HTTP, playback reads stored ranges, and the app composes them. Confirm resource/dependency access before adding any dependency. Keep new manifests/permissions limited to what the upload worker and token protection actually require. Do not add comments to implementation code or remove existing comments, per `AGENTS.md`.

### Local records and media identity

Persist only small job metadata and validated results, not another copy of the episode. A record contains local schema version, media ID, download generation/path/size/modification signature, SHA-256, local duration, API origin, non-secret credential/configuration generation, stable submission key, job ID, server status/stage, result outcome/ranges/provenance/runtime identity, timestamps and pending retry/cancellation state. Store tokens separately. Use atomic replacement and serialise updates per media to prevent worker/UI races; keep large payloads out of WorkManager `Data`.

Hash the completed local file off the playback/UI thread. Capture a download generation before hashing/uploading; discard late responses if the file or episode association changed. Recheck the exact file hash when loading an episode for validated playback after restart; cache that check for the unchanged file during the session. Unknown or changed identity means normal playback until verification completes. Never trust only episode URL, title, duration or media ID, and never hash audio in a playback tick.

Deleting or replacing media invalidates its association immediately. Removing it from the queue does not invalidate an already cached matching result. Re-adding unchanged media reuses the result. Startup reconciliation removes orphan records and restores pending jobs; a process dying between queue modification and work scheduling must not lose the episode. A recoverable corrupt record affects analysis only, never AntennaPod's episode database.

Keep the token in Android Keystore-backed encrypted private storage excluded from ordinary export/backup. Handle invalidated keys by requesting local re-entry. Mask it in settings; do not log headers, raw credentials or sensitive error payloads. Require HTTPS and forbid credential-bearing URLs. Use a dedicated client that rejects unexpected cross-origin redirects and never attaches this bearer token to ordinary feed/audio downloads.

### Queue → download → analysis flow

1. After configuration, reconcile the complete current queue. Register each audio episode lacking a matching completed result or outstanding job.
2. On queue changes, reconcile ADDED/SORTED/SET_QUEUE and membership changes. Bulk queueing, undo/re-add and play-triggered queue additions use the same route. Deduplicate by media/download generation before any expensive work.
3. For an undownloaded queued episode, request one download through AntennaPod's existing service interface, preserving network/storage constraints. Prevent duplicate requests from the resulting queue/item events. A cancelled download stays cancelled until explicit Retry or a new queue addition; reconciliation must not immediately restart it.
4. After `MediaDownloadedHandler` has persisted completed media, the item event makes it eligible for hashing/upload. Confirm it remains queued and is a readable completed audio file; ignore partial files, non-audio media and stale events.
5. Persist the hash, submission key and intended request before upload. Send the exact bytes; persist the admitted job ID immediately. Recover a lost admission response by retrying the same request/key, not creating a new analysis.
6. Poll the persisted job, fetch its completed result, validate it, atomically persist it and notify playback/UI. Admission success is not analysis success.
7. On startup, settings change and eligible network recovery, reconcile pending episodes and poll existing jobs. A configuration-origin change must not poll old job IDs against a different host or silently send queued files to the new host; clearly reset/reconfigure pending work.

One upload at a time and at most one admitted unfinished analysis from this app is the initial throughput policy. Queue the rest locally in playback queue order; other API consumers may still cause 429 responses. This deliberately stays below server limits and matches its serial worker. Completed cached results are available independently of this queue. Preserve a manual cancel/retry suppression flag so recurring reconciliation does not undo a user's action.

If an episode is removed before submission, stop its analysis-only pending work; leave AntennaPod's existing download cancellation semantics intact. If removed after admission, request cancellation when connectivity permits. Keep cancellation pending across outages, do not claim remote cancellation until acknowledged, and handle completion winning that race. Disabling integration stops new uploads and requests cancellation of active jobs; cached results may remain stored, but skipping is disabled while integration is disabled.

### API contract to implement

Base URL: configurable, initially `https://audioaddetect-pc.tail09f731.ts.net`. All analysis routes require `Authorization: Bearer <locally provisioned token>`.

| Operation | Request | Expected handling |
| --- | --- | --- |
| Submit | `POST /v1/analyses/upload?expected_sha256=<sha256>`; optional fixed `publisher_context`; `Content-Type: application/octet-stream`; raw file body; stable `Idempotency-Key`. | HTTP 202 `AnalysisStatus`; persist `job_id`. No multipart conversion or remote URL submission. |
| Track | `GET /v1/analyses/{job_id}` | Read `status`, optional `stage`, identity and error. Show stage text, not invented completion percentages. |
| Result | `GET /v1/analyses/{job_id}/result` | HTTP 200 `AnalysisResult` after completed status. Validate before storing as usable. |
| Cancel | `POST /v1/analyses/{job_id}/cancel` | HTTP 202 requests cancellation; continue tracking acknowledgement/terminal state. |
| Explicit retry | `POST /v1/analyses/{job_id}/retry` | Persist the returned status/job identity. Do not assume every terminal job is retryable. |
| Delete | `DELETE /v1/analyses/{job_id}` | HTTP 204 for terminal artifact cleanup, if needed. A remote deletion UI is deferred; local media deletion must never require API connectivity. |

Use a persisted random submission key for the exact media and fixed request parameters, scoped to API origin/configuration. Reuse it across uncertain network retries. Only generate a new key for a deliberate resubmission after expiry/deletion or changed media/input. Do not accidentally change `publisher_context` on a retry; initially use the contract's default context to minimise inputs.

Validation requires `schema_version == promotion-analysis-result-v1`, the expected job association, exact `media_sha256`, positive duration and a compatible local decoded duration. Initial duration tolerance proposal: at most 1,000 ms, to be checked against MP3/container reporting in fixtures and on the phone. If duration is unknown, wait for validation rather than guessing. Require integer ranges satisfying `0 <= start_ms < end_ms <= duration_ms`; reject an invalid response rather than silently moving its timestamps. Sort and union only overlapping/touching valid ranges for lookup, retaining original provenance; add no padding and do not bridge genuine gaps.

`valid_ranges` enables matching seeks; `valid_empty` means successful analysis with no returned sections. `degraded_fallback` remains clearly labelled and may use geometrically valid, matching ranges under the proposed MVP policy. Failed, cancelled and interrupted jobs are not empty results. Preserve `runtime_identity`, provenance and the outcome in local storage. `legacy_auto_skip_enabled: false` is historical server metadata; the API explicitly leaves playback policy to the consumer, so it must not override the owner's Auto-skip setting. No calibrated confidence is supplied.

### Network recovery and Android scheduling

| Condition | App behaviour |
| --- | --- |
| No allowed network, PC asleep, Tailscale off, DNS/connect timeout | Keep pending/unavailable status; retry network work with bounded backoff; normal playback continues. |
| 401/403 | Stop repeated requests; show credentials/access error and wait for settings correction. |
| 429 or maintenance 503 | Honour `Retry-After` when present; defer admission. Do not loop or create new keys. |
| 413 or known size/duration limit | Mark unsupported with reason; do not keep uploading. |
| Validation/hash conflict or 409 | Interpret in context: admission input conflict, result not available, retry refusal. Do not blindly retry or treat as empty. |
| Job/result expired or 404 | Keep an existing validated local result. If none exists, show expired and offer explicit fresh submission. |
| Server failed/interrupted/cancelled | Stop polling as active; expose explicit Retry where supported. No automatic repeat inference. |
| Malformed/unknown schema | Mark unusable; retain enough sanitised diagnostic status to repair; normal playback. |

Stream uploads from disk with cancellation tied to worker stop; do not load full episodes into memory. Set explicit connect/read/write and total request deadlines. Choose an upload call deadline slightly below the gateway's 120 seconds so it terminates cleanly; do not promise large uploads over slow mobile links. There is no documented resumable/chunk-upload contract, so an unsuccessful transfer may need to resend bytes with the same key.

Use durable WorkManager work for submission and short polling attempts, with unique work per media generation. Separate transport retry count from server processing status so a long queued job does not become a false failure. Initial polling target is roughly 10 seconds, backing off to 60 seconds; foreground status can refresh sooner, while Android may defer background execution. Persist next poll/retry time and reconstruct scheduling after process death. Do not keep a worker sleeping through a potentially hour-long server analysis or hold a phone wake lock while the PC runs inference.

Uploads may require a foreground notification, cancellation action and `dataSync` service type/permission; verify the existing merged manifest before adding these. Android documents foreground-worker requirements and Android 16 job-quota limits in [long-running work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running). Prefer bounded transfers and short poll jobs; test actual scheduling on Samsung before introducing a different job/service system. Implement unique-work sequencing with the documented [WorkManager chaining semantics](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/chain-work), including recovery if the process dies between persisting state and scheduling the next attempt.

### Playback policy

Load matching validated ranges asynchronously when `Media3PlaybackService` switches episodes; retain an immutable in-memory snapshot. Clear the old snapshot immediately on a media change. On result/settings changes refresh only the current episode, rejecting stale completion events. Network, hashing and disk operations never run in the seek decision path.

For playing, verified local media with Auto-skip enabled, if `start_ms <= position_ms < end_ms`, issue a normal Media3 seek to `end_ms` on the player application thread. Reuse existing service/session seek handling so notification controls, chapter navigation, progress and played-duration accounting remain consistent. Media3's thread requirement is documented in [ExoPlayer getting started](https://developer.android.com/media/media3/exoplayer/hello-world).

Start with a dedicated lightweight 250 ms skip check while playing, plus checks on resume, completed explicit seek, media readiness and fresh range arrival. Leave AntennaPod's existing one-second UI observer interval alone. At 2× speed this interval permits about 500 ms of media to pass before a seek, plus scheduling/seek latency; measure audible behaviour instead of claiming a guaranteed deadline. If the validated episode lacks any ranges, the skip timer need not run.

Guard in-flight seeks by media ID/generation and target so a slow or adjusted seek cannot repeatedly trigger a loop. Clear the guard on acknowledgement, media change or timeout; ensure bounded recovery. Test adjacent/overlapping ranges and final ranges reaching episode end through the existing queue-completion path. Use media time, not wall-clock time multiplied by speed. Only the current position being inside a range triggers skipping: seeking past an ad must not rewind to its end.

When paused, do not automatically move the user's position; check on resume. Seeking back into an ad while Auto-skip is on skips it again, so the owner can turn Auto-skip off to replay content. Manual Skip ad works with Auto-skip off but uses the same identity/range checks. Route it through the media session so the fragment does not own playback logic. The legacy service remains disabled and is not an MVP integration target.

Disable detected-ad seeks for streaming or Cast media whose fetched bytes cannot be verified; retain ordinary upstream controls and make that scope visible in status. Test interplay with intro/ending skips, speed, skip-silence, pause rewind, chapters, sleep timer and end-of-queue transitions.

## Delivery milestones

### M0 — Build and Samsung baseline (approximately half a day)

1. Create a feature branch, record upstream revision and inspect any nested instructions before changes.
2. Select installed Java 21, locate/provision Android SDK 36 and required build tools, and verify wrapper/dependency access without changing application versions.
3. Compile the unchanged app with `./gradlew :app:assembleDebug`; retain and inspect complete output.
4. Resolve ADB execution/device access. The owner connects/unlocks the Samsung phone and accepts any USB-debugging trust prompt. Record model, Android version and connection.
5. Install/start using the mandated command below. Smoke check subscribe, download, queue and playback. Preserve the baseline APK and app data/export before later updates; do not uninstall the owner's release app.
6. Check native HTTPS/token connectivity through the current pilot, without exposing the token or requiring remote diagnostic routes.

Exit: baseline build/install evidence and a reachable authenticated API from the actual device. Build/device setup failures are resolved here, not hidden behind later feature work.

### M1 — One native end-to-end episode (approximately one day)

1. Add minimal configuration, protected token entry, API transport, persistent job/result store and status UI.
2. Wire one already-downloaded queued episode through hash → raw upload → admitted job → bounded polling → validated local result.
3. Add shared range lookup, Auto-skip and the minimal manual action in the active Media3 service/player.
4. Develop parser/seek behaviour with exact-file controlled fixtures, including non-empty ranges, while backend processing proceeds separately.
5. Put this build on Samsung. Demonstrate automatic and manual seeks, then disconnect the PC/Tailscale and replay cached ranges.

Exit: first installable integrated build proving native transport, identity, persistent results and service-side seeking. Fixture-only success is an integration milestone, not final real-podcast acceptance.

### M2 — Automatic queue coverage and recovery (approximately one to two days)

1. Add coordinator initialization and reconciliation for startup, queue events, bulk/sorted additions and completed-download item events.
2. Automatically request missing downloads with the agreed Wi-Fi default/mobile opt-in and existing AntennaPod constraints.
3. Add unique durable work, one outstanding analysis policy, stable admission keys, explicit retry/cancel and clear errors.
4. Cover process restart, lost admission responses, delayed results, requeue, deletion/redownload, settings changes and unavailable service. Complete status UI and translations.
5. Install an update without clearing data; prove an undownloaded queued episode reaches analysis automatically and cached results remain intact.

Exit: the owner's normal queueing workflow needs no separate analysis action, and outages/restarts do not disrupt listening or repeat work unnecessarily.

### M3 — Real episode qualification and release to phone (approximately one to two days)

1. Choose a small owner-permitted episode set, within API ceilings, including a real episode expected to return ranges. Avoid a broad detector benchmark.
2. Run at least one fresh full native pipeline on the current backend; record download/upload/queue/analysis timing, bytes, hash, outcome and resource-related failures. Coordinate any backend repair in its own repository rather than redesigning it in this fork.
3. Perform the acceptance matrix below on Samsung, including screen-off/Bluetooth playback and Wi-Fi-off/mobile-data Tailscale access when allowed.
4. Fix integration failures, compile again, run relevant module tests, then install the final MVP update.
5. Retain APK, source revision and test notes under a small delivery record. Keep the same package/signing identity for updates and protect any signing key. Document configuration, retry/cancel, PC availability, cached offline use and rollback.

Exit: objective R1–R12 evidenced on the phone. If fresh podcast processing still fails, deliver the working integration build with that limitation and keep the usable-podcast acceptance open; do not call the full objective complete.

## Verification and acceptance matrix

| Scenario | Required observation | Method |
| --- | --- | --- |
| Already downloaded queued episode | One submission, validated hash, saved result | API client/store tests and Samsung |
| Undownloaded, bulk and keep-sorted queue additions | Download then analysis for every eligible item; no duplicates | Coordinator tests and Samsung |
| Lost admission response, process death after 202 | Same key/job recovered, no repeated inference | Fake HTTP admission test; restart app on phone |
| Auto-skip off/on and manual Skip ad | No unsolicited seeks when off; correct range-end seek when on/manual | Range-policy tests and audible Samsung playback |
| Resume/seek into range; 0/end/overlap/touching ranges | Correct boundaries, no loop, no stale media seek | Pure policy tests; player smoke check |
| 1×/2×, silence skipping, screen off, Bluetooth | Skips remain based on media position; ordinary controls work | Samsung listening check |
| Empty/degraded/failed/interrupted/cancelled/invalid result | Each state has the defined policy/status; no failure shown as empty | Parser/worker tests and selected phone cases |
| PC/Tailscale/network unavailable | Ordinary playback works; pending/retry visible | Samsung with connection disabled |
| Offline cached playback and app restart | Matching saved ranges continue working | Samsung in offline mode |
| Redownload/replace/delete while job active | Old result never applied; orphan state cleaned | Store/coordinator tests; phone redownload |
| Queue removal, cancel and integration disable | No automatic resubmission; remote cancellation reconciles | Worker tests and phone status check |
| Existing features | Subscriptions, refresh, queue, downloads, streaming, speed, chapters, sleep timer, notifications/headset controls and available sync/import/export paths remain usable | Existing relevant tests and focused smoke checks |
| Remote playback | Cast/remote streaming remains usable without applying unverified ranges | Available-device smoke check; otherwise record untested scope |
| Native real fresh episode | Complete queue → exact-file upload → result → skip on Samsung | Recorded live acceptance; synthetic/browser evidence insufficient |
| Update and credentials | Pilot app data/results survive update; token absent from outputs/export | Install update and review new storage/logging paths |

Add focused tests for consequential behaviour: admission idempotency/recovery, result validation, cache invalidation, coordinator coverage and seek decisions. Do not write tests for simple resource edits or unrelated code. Use existing infrastructure; add a network test dependency only if needed to test lost/failed responses meaningfully.

Follow `AGENTS.md` exactly for compilation and execution, from the repository root without `cd`. Do not filter or truncate compiler output:

```sh
./gradlew :app:assembleDebug
```

Only after compilation succeeds, run the relevant module `:test` tasks using `./gradlew --console=plain`, for example:

```sh
./gradlew --console=plain :net:common:test :storage:database:test :net:download:service:test :playback:service:test
```

Install and start the phone build with:

```sh
./gradlew --console=plain :app:installPlayDebug && adb shell monkey -p de.danoeh.antennapod.debug 1
```

Before opening any PR, run `./gradlew checkstyle lint`, inspect the repository PR template, and follow the owner's PR/issue conventions. A PR is not needed to deliver the initial personal sideload. Do not fix unrelated warnings or broaden the diff to pass checks.

## Delivery risks and decisions still to validate

| Risk or unknown | Resolution that preserves speed |
| --- | --- |
| Fresh ordinary podcast processing is not yet qualified | Validate one permitted real episode early; repair backend capacity separately. Keep acceptance open if it fails. |
| Upload deadline on slow mobile links | Wi-Fi default, bounded transfer, clear retry/error; measure actual selected sizes. No unplanned resumable-upload backend project. |
| Samsung battery/background restrictions and Android version | Establish device baseline first; test durable work/screen-off playback. Change device settings only if observed operation requires it. |
| Java/SDK and restricted ADB environment | Select installed Java 21; locate SDK 36 and establish authorised host/device execution. No build success or device access is assumed. |
| Detector returns incorrect or degraded sections | Display outcome, preserve provenance, support immediate Auto-skip off/manual replay; do not invent accuracy guarantees. |
| Duration reporting/seek adjustment across audio formats | Validate the proposed 1-second duration tolerance and seek-loop guards on actual files. Unsupported identity falls back to normal playback. |
| Debug package has separate data/signing | Document pilot coexistence and import limitations; preserve pilot identity/key for updates. Full release-data migration and custom branding are deferred. |

Only device model/Android version, a selected small episode set and actual native capacity/behaviour measurements remain necessary for delivery. Hosting choice, private Tailscale access and automatic queue downloads have already been decided; do not reopen them.

## References

- [MVP objective and requirements](ad-skip-objective.md).
- [Backend pilot](../../AudioAdDetect/docs/android-private-pilot-plan-2026-10-10.md), [onboarding](../../AudioAdDetect/docs/deployment-private-onboarding-2026-10-10.md), [contract](../../AudioAdDetect/release/consumer-api.md), [OpenAPI](../../AudioAdDetect/release/openapi-v1.json) and [operation envelope](../../AudioAdDetect/docs/deployment-podcast-envelope-a3-v6-2026-10-10.json).
- [Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve): private access through the existing tailnet; no public exposure needed.

No app code changed and no build, test or phone installation was performed in this planning task. Java and ADB were inspected only; phone enumeration was blocked by the restricted ADB listener.
