# AntennaPod AdSkip MVP objective

Date: 12 October 2026. Status: MVP delivered on Samsung SM-A266B / Android 16; latest installed app source is `e375f6158`. Fresh full-podcast non-empty/manual/automatic/offline checks and the 577 consumer playback check passed. The owner accepted all remaining checks by assumption; unexecuted checks remain distinguished from measured evidence.

## Objective

Deliver a usable Android app on the owner's Samsung phone, based on this AntennaPod fork, that automatically submits queued episodes to the existing AudioAdDetect API and skips its returned ad sections when the owner enables automatic skipping. Preserve AntennaPod's existing podcast functionality. Prioritise a small working deployment over additional features or architectural changes.

The owner confirmed that adding an episode to the queue should automatically download it and initiate analysis. The first deployment is a personal sideloaded build using the existing PC service through private Tailscale HTTPS.

## Delivered MVP and acceptance record

The final APK is installed with the existing Debug package/signing identity and preserved pilot configuration/results. Cancellation queue cleanup is fixed in `29183e5d3`; `c8858a17a` exposes analysis status/Retry/Cancel for pending episodes directly from their menus and removes the terminal “Stage: null” display. The latest in-place rebuild at `e375f6158` also passed build/install and was independently matched against the installed APK (SHA-256 `913e43fc24e9028b84e8188c4cba6f1067a0eed94001ce650e5c10c255d7092c`). The 577 result hash matched the downloaded media; at 01:30 both the manual action and Auto-skip reached the first range's 04:13.760 end. The Skip ad action is contextual and hidden outside detected ranges.

Retained module reports total 1,018 executions across variants with zero failures/errors/skips. The last changed-module run contributed 444 executions; the total combines it with retained unchanged-module reports and is not a new all-module run or 1,018 unique tests. The final menu change was compiled and checked on Samsung.

| Requirement group | Measured evidence | Acceptance scope |
| --- | --- | --- |
| R1: preserve AntennaPod | Update/data preservation and baseline podcast checks; corrupt-record/I/O cancellation regressions pass. | Remaining normal-use paths accepted by owner assumption. |
| R2/R3: automatic queue/exact-file analysis | Fresh Coffee House Shots queue → download → upload → result; dedicated bulk queue and mobile-permission combinations. | Complete keep-sorted/transfer interruption matrix not executed. |
| R4/R5/R11: configuration, persistence, recovery | Authenticated native HTTPS, persisted job/result, pending-item recovery controls, cancellation suppression after sorting/restart. | Remaining deletion/redownload/admission fault paths accepted by assumption. |
| R6/R7/R8/R10: skip controls and offline media | Controlled 1×/2× skips; owner-confirmed screen-off resume; real manual/automatic skips and offline restart on matching bytes. | Full 14-position boundary and Bluetooth matrix not executed. |
| R9/R12: useful app on Samsung | Fresh real result with three ranges; owner confirmed useful skips/programme preservation and queue workflow; final APK installed. | Outstanding owner checks waived for this personal MVP delivery. |

Evidence types remain separate: fresh short-recording `valid_empty`, retained-job recovery replay, synthetic seek ranges, and fresh full-podcast `degraded_fallback` with three live ranges. The [phone handover](ad-skip-phone-checklist.md) gives APK identity/settings/use; the [validation record](ad-skip-m0-m1-validation.md) retains measured observations and limits.

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

The current pilot documentation records the private endpoint as `https://audioaddetect-pc.tail09f731.ts.net`. The Samsung has subsequently passed authenticated native upload/status/result over Tailscale for the short owned programme, as recorded in the delivery evidence. This documentation review performs no new live availability check. The older conversation's “endpoint is not running yet” statement is superseded by the current pilot/onboarding documents.

Use ordinary HTTPS and the separately installed Tailscale Android client. The private gateway accepts uploaded media; do not use remote URL submissions or require SSH, a Tailscale SDK, a paid domain, public hosting or changes to PC boot/sleep policy. Preserve standard certificate verification and the API's bearer authentication.

The current private service is configured for up to 120 minutes per recording (the package default is 60 minutes), 256 MiB, four active jobs per owner, two concurrent uploads and one serial analysis worker. These are admission ceilings, not measured supported capacity. The deployed gateway bounds an upload to 120 seconds. Queue work locally and expose rejected/oversized/slow-upload cases rather than blocking playback.

The backend's documented full podcast attempt failed at an 8 GiB memory ceiling; a checkpoint-assisted retry completed at 16 GiB and returned `degraded_fallback`. A subsequent fresh native Coffee House Shots episode (21:49.693, 20,955,329 bytes) completed in 564.933 seconds with three validated degraded ranges and useful skipping confirmed by the owner. This qualifies the selected episode, not every podcast or backend capacity ceiling. Detector accuracy and backend capacity are separate from correct Android integration; the app cannot promise every ad will be detected or that no programme content will ever be included.

## Deferred work

Public access, Play Store publication, paid hosting, multi-user accounts, detector/model changes, on-device inference, instant streaming analysis, remote-player timestamp matching, per-podcast policies, confidence thresholds, timeline annotations, range editing, analytics dashboards, ad removal from audio files and custom branding are deferred. Preserve upstream licence notices. No broad refactoring, dependency upgrades or unrelated warning fixes are part of this project.

## Definition of done

The MVP is complete when R1–R12 have recorded evidence, the authorised build compiles, and the owner can use the installed Samsung app to queue an episode and later play its exact downloaded audio with optional automatic skipping. A real fresh API analysis and a result containing ranges must be exercised; a synthetic playback test proves seek behaviour but cannot replace the live pipeline check. Cached skipping must also work offline and after restart. The handover includes the APK, baseline/source revision, installation/update method, settings instructions, tested phone/Android version, measured episode size/duration and known limitations.

For this delivery, the owner's latest instruction supersedes the remaining physical acceptance gates: “Owner checks no longer available. Assume all outstanding checks passed. Continue development of MVP and deployment of final app to Samsung.” The MVP is delivered under that acceptance assumption. Fresh real non-empty playback/offline use, compilation and installation were actually demonstrated; unavailable Bluetooth and other unexecuted checks are not recorded as measured passes.

## Sources

- [Implementation plan](ad-skip-implementation-plan.md).
- [Backend Android pilot plan](../../AudioAdDetect/docs/android-private-pilot-plan-2026-10-10.md).
- [Private deployment onboarding and qualification](../../AudioAdDetect/docs/deployment-private-onboarding-2026-10-10.md).
- [Consumer API contract](../../AudioAdDetect/release/consumer-api.md) and [OpenAPI schema](../../AudioAdDetect/release/openapi-v1.json).
- [Measured podcast operation envelope](../../AudioAdDetect/docs/deployment-podcast-envelope-a3-v6-2026-10-10.json).

Backend references are to the sibling checkout on this PC; the endpoint, constraints and MVP contract are summarised here so the core objective remains readable without that checkout.
