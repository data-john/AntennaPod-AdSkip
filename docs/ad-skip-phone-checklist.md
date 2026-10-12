# Samsung MVP handover and acceptance checklist

Delivered 11 October 2026. Device: Samsung SM-A266B / Android 16. The owner accepted outstanding checks by assumption and requested final deployment. The session checklist below is retained as a reference, not a blocker to this delivery.

## Installed build and completed checks

Final installed app source: `c8858a17a`. APK: `artifacts/ad-skip/phone/final-mvp/antennapod-adskip-mvp-play-debug.apk`, SHA-256 `5cba9a24eae39291491d9d09a5b2ea3adf8c83b62e0867b0a63ef5c579c1dc79`. The installed APK was pulled and independently matched this hash. Package: `de.danoeh.antennapod.debug`, version 3.12.3 (3120395). Signing matches baseline certificate SHA-256 `4aa8d89b5e7491eb5828b6a633837b34a467b21b1668fd6e2e2038f1103f1214`. Earlier candidate/recovery APKs remain archived and are superseded. Final build/install logs, manifest, settings, pending-status UI and cancellation records are retained in `final-mvp`; service regression reports and fresh-podcast evidence remain in `cancel-cleanup`.

Measured checks include baseline/update preservation; cancellation bookkeeping regressions; native authenticated upload/status/result; fresh short-recording valid-empty completion; controlled synthetic skips; fresh Coffee House Shots completion with three degraded live ranges, manual/automatic skipping and offline restart; owner-confirmed useful skips/workflow and controlled screen-off resume; dedicated bulk queue/mobile-permission gating and cancellation suppression after sorting/restart. The full boundary/Bluetooth/deletion/admission matrix and remaining normal-use paths were accepted by assumption, not executed.

Final settings: real origin/encrypted local token preserved, integration enabled, Auto-skip off, mobile analysis off, AntennaPod mobile episode downloads off, keep-sorted off. Wi-Fi/mobile connectivity restored; playback stopped; only the real Coffee episode remains queued. Temporary test feed forwarding was removed.

## Using the installed MVP

Keep the PC detector awake and Tailscale connected for new analyses. Add an episode to the queue: with permitted connectivity it downloads and submits automatically. Open the episode menu → Ad analysis status to see progress or use Retry/Cancel, including before download/playback. A cancelled automatic download remains suppressed until an explicit retry/re-add.

Enable Auto-skip in Settings → Playback when you want detected sections skipped. Turn it off to replay them; the player menu offers Skip ad while inside a validated range. The analysed downloaded file and saved ranges work offline after restart. The tested Coffee episode returned a visibly degraded result, so detection may be incomplete; exact-file validation protects against applying ranges to different audio. New analysis requires the PC/private service; pending or failed analysis does not prevent ordinary listening.

Update by installing the matching-signature APK over Debug without uninstalling it. Keep release AntennaPod installed; its data is separate. The baseline rollback procedure below preserves the pilot data.

## Original acceptance session (remaining checks accepted by assumption)

1. **Corrected build installed.** Follow the plan's focused cancellation-bookkeeping regression test/fix. Compile with `./gradlew :app:assembleDebug`, then run the relevant module tests. Preserve the current APK/data before an update; use the recorded JDK/SDK/build-directory setup and `./gradlew --console=plain :app:installPlayDebug && adb shell monkey -p de.danoeh.antennapod.debug 1`. Verify pilot subscriptions/results/token survived.
2. **Start the real episode early.** Choose one already permitted, declared normal-length ad-bearing podcast, within 256 MiB and the private service’s 120-minute limit. On Wi-Fi with Tailscale and PC awake, add its undownloaded audio to the queue, Auto-skip off. Require automatic download → raw exact-file upload → polling → validated result. Record bytes/hash/duration, job/key, timings and outcome. No manual Download/Analyse and no seeded/cached job substitution. If inference fails, retain the error and identity, diagnose in AudioAdDetect and retry explicitly where supported; do not silently resubmit or claim an empty result.
3. **Finish controlled seek checks while analysis runs.** Use the existing deterministic WAV and labelled synthetic ranges, independently matching its manifest hash. Check all 14 manifest positions, gaps and episode end at 1×/2×; manual Skip ad paused/playing; Auto-skip off/on; resume, seek into/past ranges and pause rewind. Confirm unrelated episode/queue events do not clear working ranges. Label all observations controlled fixture evidence.
4. **Check background and output.** Turn the screen off and verify at least two skips, pause/resume and notification/headset controls. Repeat with a paired Bluetooth device. The owner confirms audible behaviour; session-position snapshots alone do not prove clean sound. Check sleep timer, chapters, speed/silence and intro/ending skips remain usable. Restore settings afterward.
5. **Check metered policy and recovery.** With Wi-Fi off/Tailscale on, test neither/one/both mobile permissions (integration and AntennaPod episode download). Full-audio work waits unless both allow it; status/cancel may use connected mobile data. Change permissions/connectivity during dedicated queued/running work. Cancel then sort/move/restart: suppression remains; explicit Retry/re-add can resume. Check bulk/keep-sorted queueing, force-stop/restart, pending-job removal/deletion/replacement and terminal cancel/completion races. Keep original admission IDs/keys and reject old ranges for changed bytes. Do not corrupt ordinary user data; use dedicated fixtures or injected desktop tests for storage faults.
6. **Accept live non-empty and offline playback.** When the real job completes, validate its actual result, hash/duration and outcome/provenance. For non-empty live ranges, test manual/automatic range-end seeks and owner listening. Then disconnect networking, force-stop/restart and replay those same downloaded bytes/ranges. Valid-empty completion does not close this non-empty gate; another already permitted ad-bearing sample may be needed. Restore original networking/mobile/Auto-skip preferences.
7. **Complete normal-use review and handover.** Smoke check feed refresh/subscriptions, queue, downloads/streaming, available sync/import/export and ordinary controls. Obtain the owner's final normal-listening acceptance; the previous yes confirmed controlled tones only. Retain final APK/source/hash/signing evidence, complete logs/reports, test episode/result/timings and limitations. Append observations to the validation record. For this delivery, outstanding checks were subsequently accepted by owner assumption.

## Optional fixture harness

The test-only harness `scripts/ad-skip-fixture-server.py` serves `/feed.xml`, `/seek-fixture.wav` and compatible analysis routes. The generator is `python3 scripts/ad-skip-fixtures.py`. A private HTTPS instance requires a certificate valid/trusted for the chosen host and a protected fixture-token file:

```sh
python3 scripts/ad-skip-fixture-server.py artifacts/ad-skip/m1/fixtures --base-url https://<certificate-valid-private-host>:8443 --bind <private-address> --cert <certificate-chain.pem> --key <private-key.pem> --token-file <private-fixture-token-file>
```

Do not assume this optional HTTPS fixture endpoint is deployed. It is separate from the live detector and the existing seeded tone-cache checks. Finish/cancel real outstanding work before temporarily changing origin/token. Keep fixture credentials local, preserve synthetic provenance and restore the real configuration afterward. Do not weaken production HTTPS checks or add trusted-range import UI just for acceptance.

Rollback: disable integration and install the retained baseline over Debug through the baseline-source Gradle workflow, without uninstalling the app. Preserve pilot data and release AntennaPod. The original candidate APK is not the baseline rollback artifact.

See the [implementation plan](ad-skip-implementation-plan.md#remaining-mvp-work), [objective](ad-skip-objective.md) and chronological [validation record](ad-skip-m0-m1-validation.md). MVP development/deployment is complete under the owner's acceptance instruction; measured and assumed outcomes remain separate.
