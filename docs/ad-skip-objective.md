# AntennaPod AdSkip MVP objective

Date: 11 October 2026. Status: core integration implemented and desktop-tested; automatic queue downloads and Samsung acceptance remain pending. Progress reviewed at source commit `872a5af4a`.

## Objective

Deliver a usable Android app on the owner's Samsung phone, based on this AntennaPod fork, that automatically submits queued episodes to the existing AudioAdDetect API and skips its returned ad sections when the owner enables automatic skipping. Preserve AntennaPod's existing podcast functionality. Prioritise a small working deployment over additional features or architectural changes.

The owner confirmed that adding an episode to the queue should automatically download it and initiate analysis. The first deployment is a personal sideloaded build using the existing PC service through private Tailscale HTTPS.

## Current progress and temporary delivery constraint

The branch `codex/ad-skip-m0-m1` contains the transport/store foundation (`c8b479850`), integrated settings/workers/Media3 skipping (`1d44f8e28`), build/test evidence and a deterministic exact-audio fixture. The baseline and integrated APK hashes match the [delivery record](ad-skip-m0-m1-validation.md). Review of the retained XML reports confirms 910 test executions across variants with zero failures/errors; these are existing recorded results, not a new test run or 910 unique test methods.

| Requirement group | Implemented/evidenced now | Remaining acceptance or implementation |
| --- | --- | --- |
| R1, R12: AntennaPod base and deployment | Baseline and integrated debug APKs built; existing module tests passed. | Samsung installation/update, data preservation, normal-use smoke checks and owner usability review. |
| R2, R3: queue analysis and exact downloads | Coordinator schedules reconciliation on queue/item events and startup; worker uploads completed local audio with SHA-256. | Missing queued audio is currently skipped by the worker, rather than automatically downloaded. Add downloads, waiting/suppression state and real queue/coordinator tests. |
| R4, R5, R11: configuration, jobs and recovery | HTTPS-only dedicated transport, Keystore-protected token, persistent jobs/results, bounded outage attempts, retry/cancel UI and mocked admission recovery tests. | Actual Keystore/network/WorkManager execution; deletion/cancellation and configuration/network-change recovery checks. |
| R6, R7, R8, R10: automatic/manual skipping and matching offline cache | Active Media3 seek logic, asynchronous cached-file verification, replacement invalidation, range/seek tests and deterministic tone fixture. | Actual decoded-audio seeks, restart/offline playback, screen-off/Bluetooth and audible boundaries on Samsung. |
| R9: ordinary listening during failures | Analysis is optional and separate from playback; policy tests reject invalid/stale/remote ranges. | Runtime failure-path and upstream feature smoke checks; keep regression coverage focused on changed integration paths. |

The owner has stated that Samsung USB access and owner testing are unavailable for several hours. This blocks physical-device acceptance, not routine implementation, desktop tests, package preparation or review of the agreed scope. Continue those activities without waiting for owner review. Do not change the requirements, introduce new hosting/model work or mark Samsung checks complete using fixtures, mocks or an emulator.

The interim target is a compiled, regression-tested MVP candidate with automatic queue downloads, recovery gaps addressed, retained APK and a short prepared phone checklist. Final completion still requires R12 and the real episode/offline playback evidence below. A detailed ordered work queue is in the [implementation plan](ad-skip-implementation-plan.md#work-to-complete-without-usb-or-owner-review).

## Required behaviour

| ID | Requirement | Acceptance evidence |
| --- | --- | --- |
| R1 | Use the fork as the app base; retain subscriptions, feed refresh, queue management, downloads, streaming and ordinary playback. | Baseline and modified app smoke checks; existing data survives an update of the pilot build. |
| R2 | Automatically register every queued audio episode for analysis, including single additions, bulk additions, sorted queues and restored/existing queues when integration is enabled. | Each eligible episode progresses from waiting for download to submission without a separate Analyse action. Reordering or re-adding unchanged media creates no duplicate analysis. |
| R3 | Automatically download queued episodes that lack a local file, subject to the selected network policy and available storage. Upload the exact completed file that the phone will play. | Queued undownloaded episode downloads and uploads; upload hash matches playback media. No URL-based server download or upload of partial files. |
| R4 | Configure the private HTTPS base URL and bearer token locally. | Correct credentials admit a job from the Samsung phone; incorrect credentials produce a recoverable setup error. No token is embedded in source, APK, logs or documents. |
| R5 | Persist analysis jobs and results, with status visible to the user. | Leaving/restarting the app resumes tracking the same job. Waiting, running, unavailable, failed, interrupted, cancelled, ready and valid empty results remain distinguishable. |
| R6 | Provide a persistent global Auto-skip setting. When enabled, seek to the end of a matching returned range during local playback. When disabled, do not automatically seek. | Toggle takes effect during playback; advancing, resuming and seeking into a range work with the screen off and at different playback speeds. |
| R7 | Allow deliberate manual skipping of the current detected ad section. | A minimal Skip ad player action seeks to that range's end with Auto-skip off. It is unavailable outside a valid detected range. |
| R8 | Retain validated results locally for matching downloaded audio. | Downloaded and analysed episodes continue playing and skipping after Tailscale disconnects, the PC sleeps or the phone is offline, including after app restart. |
| R9 | Analysis must never prevent normal listening. | PC outage, missing credentials, API errors, unsupported media and pending analyses leave AntennaPod playback usable; status explains the absence of skipping. |
| R10 | Never apply ranges from a different audio file or invalid result. | Hash mismatch, replacement/redownload, malformed response and incompatible duration disable skipping for that result. |
| R11 | Recover without repeated uncontrolled uploads or inference. | Stable admission idempotency, bounded network retries, explicit retry for failed/interrupted jobs and a user cancellation path. |
| R12 | Install, configure and validate the app on the owner's actual Samsung phone. | Native queue → download → upload → result → automatic skip succeeds on a real episode, with a retained APK and short usage instructions. A browser or emulator test alone does not satisfy this requirement. |

R7 is a proposed interpretation of “allows the user to skip these sections”: a small manual action as well as the explicitly requested automatic mode. It should not grow into a separate editing or review interface.

## MVP defaults and scope decisions

These implementation defaults are proposed to make the first build useful quickly; they are separate from the owner's confirmed requirements.

- Analysis runs after API configuration and explicit integration enablement. It continues when Auto-skip is off, so results are ready if skipping is later enabled. The integration enable/disable control stops new submissions.
- Auto-skip defaults to off and is remembered after the owner enables it. Turning it off immediately permits replaying skipped content.
- Automatic download and upload default to Wi-Fi/unmetered connectivity. Provide an explicit Allow mobile data option; never silently override an existing stricter AntennaPod download restriction. Queued episodes remain pending until allowed connectivity is available.
- The MVP applies validated API union ranges, including the contract's acceptable self-promotion ranges. Preserve paid/self-promotion provenance. A `degraded_fallback` result can be used if media and range validation pass, but its status must identify the degraded outcome. Do not invent confidence scores or quietly describe it as fully valid analysis.
- Analyse/download ahead of listening; start playback immediately if the owner chooses to listen before results are ready. New analyses require the PC to be awake and the phone's Tailscale connection active.
- Automatic skipping initially covers the phone's downloaded audio, including wired/Bluetooth output. Existing streaming, Cast, Android Auto and Wear functionality must remain usable, but ad skipping of separately fetched remote/streamed bytes is outside the initial scope. Android Auto controls of the same verified local phone playback should continue to benefit from service-side skipping.
- Server expiry does not invalidate a result already saved for unchanged local audio. Remove the local association when the episode/media is deleted or replaced. Do not store results only in Android's evictable cache directory.
- Keep the existing debug application ID for initial sideloading, allowing the pilot to coexist with an installed release AntennaPod. These installations have separate data; an OPML import can copy subscriptions, but is not a full history/queue migration.

## Backend and deployment constraints

The current pilot documentation records the private endpoint as `https://audioaddetect-pc.tail09f731.ts.net`. This is documented deployment state, not a live check performed by this Android planning task. The phone browser upload/result/playback test is complete; native Android integration is pending. The older conversation's “endpoint is not running yet” statement is superseded by the current pilot/onboarding documents.

Use ordinary HTTPS and the separately installed Tailscale Android client. The private gateway accepts uploaded media; do not use remote URL submissions or require SSH, a Tailscale SDK, a paid domain, public hosting or changes to PC boot/sleep policy. Preserve standard certificate verification and the API's bearer authentication.

The documented admission limits are 256 MiB per recording, one hour duration, four active jobs per owner, two concurrent uploads and one serial analysis worker. These are ceilings, not measured supported capacity. The deployed gateway bounds an upload to 120 seconds. Queue work locally and expose rejected/oversized/slow-upload cases rather than blocking playback.

The backend's documented full podcast attempt failed at an 8 GiB memory ceiling; a checkpoint-assisted retry completed at 16 GiB and returned `degraded_fallback`. Uninterrupted fresh analysis of normal podcasts remains unqualified. A real fresh episode must be demonstrated before declaring the MVP usable for that episode class. Detector accuracy and backend capacity are separate from correct Android integration; the app cannot promise every ad will be detected or that no programme content will ever be included.

## Deferred work

Public access, Play Store publication, paid hosting, multi-user accounts, detector/model changes, on-device inference, instant streaming analysis, remote-player timestamp matching, per-podcast policies, confidence thresholds, timeline annotations, range editing, analytics dashboards, ad removal from audio files and custom branding are deferred. Preserve upstream licence notices. No broad refactoring, dependency upgrades or unrelated warning fixes are part of this project.

## Definition of done

The MVP is complete when R1–R12 have recorded evidence, the authorised build compiles, and the owner can use the installed Samsung app to queue an episode and later play its exact downloaded audio with optional automatic skipping. A real fresh API analysis and a result containing ranges must be exercised; a synthetic playback test proves seek behaviour but cannot replace the live pipeline check. Cached skipping must also work offline and after restart. The handover includes the APK, baseline/source revision, installation/update method, settings instructions, tested phone/Android version, measured episode size/duration and known limitations.

The original planning task changed documents only; subsequent implementation has built the APKs and produced the recorded desktop tests above. This progress review changes planning documents only and does not claim a new compilation, test run, live API submission or Samsung installation. Physical-device and real-podcast acceptance remain open.

## Sources

- [Implementation plan](ad-skip-implementation-plan.md).
- [Backend Android pilot plan](../../AudioAdDetect/docs/android-private-pilot-plan-2026-10-10.md).
- [Private deployment onboarding and qualification](../../AudioAdDetect/docs/deployment-private-onboarding-2026-10-10.md).
- [Consumer API contract](../../AudioAdDetect/release/consumer-api.md) and [OpenAPI schema](../../AudioAdDetect/release/openapi-v1.json).
- [Measured podcast operation envelope](../../AudioAdDetect/docs/deployment-podcast-envelope-a3-v6-2026-10-10.json).

Backend references are to the sibling checkout on this PC; the endpoint, constraints and MVP contract are summarised here so the core objective remains readable without that checkout.

## Interim candidate delivered — 11 October 2026

The independent development outcome is now a compiled/tested candidate with automatic queued-audio downloads, network/cancellation controls, retained remote-job recovery and playback-cache reuse. The candidate validation record reports 990 executions without failures/errors and verified baseline-compatible package/signing identity. A test-only HTTPS fixture harness and phone checklist are prepared. Samsung/native playback, Tailscale, offline/restart, fresh real-episode analysis and owner acceptance remain required for the original device objective; none is inferred from desktop tests.
