package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import de.danoeh.antennapod.model.MediaMetadataRetrieverCompat;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.event.adskip.AdSkipEvent;
import de.danoeh.antennapod.model.adskip.AdSkipState;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.net.common.adskip.AdSkipResultParser;
import de.danoeh.antennapod.net.common.adskip.AudioAdDetectClient;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public class AdSkipWorker extends Worker {
    private static final String TAG = "AdSkipWorker";
    private volatile AudioAdDetectClient client;
    private static volatile AudioAdDetectClient uploading;
    private final AdSkipStore store;
    private JSONObject record;
    private FeedMedia media;
    private boolean downloadsPending;

    public AdSkipWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
        store = new AdSkipStore(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        long delay = 0;
        try {
            long[] resume = getInputData().getLongArray("resume_downloads");
            long priorityMediaId = getInputData().getLong("priority_media_id", 0);
            downloadsPending = AdSkipDownloads.reconcile(getApplicationContext(), store, DBReader.getQueue(),
                    resume == null ? new long[0] : resume);
            if (!select(priorityMediaId)) {
                downloadsPending |= AdSkipDownloads.reconcile(getApplicationContext(), store, DBReader.getQueue(),
                        new long[0]);
                if (downloadsPending && !isStopped()) {
                    AdSkipCoordinator.schedule(getApplicationContext(), true, 60);
                }
                return Result.success();
            }
            if (record.optLong("next_attempt") > System.currentTimeMillis()) {
                delay = (record.getLong("next_attempt") - System.currentTimeMillis() + 999) / 1000;
            } else {
                delay = advance();
            }
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "Credentials error", e);
            update(AdSkipState.CREDENTIALS);
        } catch (AudioAdDetectClient.ApiException e) {
            Log.w(TAG, "API error " + e.code, e);
            if (e.code == 401 || e.code == 403) {
                update(AdSkipState.CREDENTIALS);
            } else if (e.code == 413 || e.code == 422) {
                update(AdSkipState.UNSUPPORTED);
            } else if (e.code == 404 || e.code == 410) {
                update(AdSkipState.EXPIRED);
            } else if (e.code == 429 || e.code == 503 || e.code >= 500) {
                delay = unavailable(e.retryAfterSeconds);
            } else {
                update(AdSkipState.INVALID);
            }
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "Record/runtime error", e);
            update(AdSkipState.INVALID);
        } catch (IOException e) {
            Log.w(TAG, "Network error", e);
            delay = unavailable(0);
        } finally {
            if (record != null) {
                EventBus.getDefault().post(new AdSkipEvent(record.optLong("media_id", -1)));
            }
        }
        if (!isStopped() && (record != null || downloadsPending)) {
            AdSkipCoordinator.schedule(getApplicationContext(), true, delay);
        }
        return Result.success();
    }

    private boolean select(long priorityMediaId) throws IOException, JSONException {
        List<FeedItem> queue = DBReader.getQueue();
        if (priorityMediaId > 0) {
            return selectPriority(priorityMediaId, queue);
        }
        List<Long> queued = new ArrayList<>();
        for (FeedItem item : queue) {
            if (item.getMedia() != null) {
                queued.add(item.getMedia().getId());
            }
        }
        for (long id : store.mediaIds()) {
            FeedMedia candidate = DBReader.getFeedMedia(id);
            JSONObject saved;
            try {
                saved = candidate == null ? store.read(id) : store.readForMedia(candidate,
                        AdSkipPreferences.origin(getApplicationContext()),
                        AdSkipPreferences.generation(getApplicationContext()));
            } catch (JSONException | IOException e) {
                continue;
            }
            if (saved == null) {
                continue;
            }
            if (candidate == null && !saved.has("job_id") && !saved.optBoolean("admission_uncertain")) {
                store.delete(id);
                continue;
            }
            boolean changed = candidate == null || !AdSkipStore.signature(candidate).equals(saved.optString("signature"));
            if ((changed || saved.optBoolean("local_invalid"))
                    && Arrays.asList("COMPLETED", "FAILED", "INTERRUPTED", "CANCELLED")
                    .contains(saved.optString("server_status"))) {
                store.delete(id);
                continue;
            }
            if (changed && (saved.has("job_id") || saved.optBoolean("admission_uncertain"))) {
                saved.remove("result");
                saved.put("local_invalid", true).put("cancel_pending", true);
                store.save(saved, saved.getString("generation"));
            } else if (changed && !saved.optString("signature").isEmpty()) {
                store.delete(id);
                continue;
            }
            if (!saved.has("result") && (saved.has("job_id") || saved.optBoolean("admission_uncertain"))
                    && !terminal(saved.optString("status"))) {
                if ((!queued.contains(id) || !AdSkipPreferences.isEnabled(getApplicationContext()))
                        && !saved.optBoolean("cancel_pending")) {
                    store.request(id, true);
                    saved = store.read(id);
                }
                media = candidate;
                record = saved;
                return true;
            }
            if ((saved.optBoolean("cancel_pending") && !terminal(saved.optString("status")))
                    || saved.optBoolean("retry_pending")
                    && (saved.has("job_id") || saved.optBoolean("admission_uncertain"))) {
                media = candidate;
                record = saved;
                return true;
            }
        }
        for (long id : store.mediaIds()) {
            JSONObject saved;
            try {
                saved = store.read(id);
            } catch (IOException | JSONException e) {
                continue;
            }
            if (saved != null && (saved.optBoolean("corrupt_record") || saved.optBoolean("admission_uncertain"))
                    && terminal(saved.optString("status"))) {
                return false;
            }
            if (saved != null && saved.has("job_id") && !saved.has("result")
                    && Arrays.asList("QUEUED", "RUNNING", "COMPLETED").contains(saved.optString("server_status"))) {
                return false;
            }
        }
        if (!AdSkipPreferences.isEnabled(getApplicationContext())) {
            return false;
        }
        for (FeedItem item : queue) {
            FeedMedia candidate = item.getMedia();
            if (candidate == null || !candidate.localFileAvailable() || !candidate.fileExists()
                    || candidate.getMediaType() != MediaType.AUDIO) {
                continue;
            }
            JSONObject saved;
            try {
                saved = store.readForMedia(candidate, AdSkipPreferences.origin(getApplicationContext()),
                        AdSkipPreferences.generation(getApplicationContext()));
            } catch (IOException | JSONException e) {
                continue;
            }
            if (saved == null) {
                saved = store.create(candidate, AdSkipPreferences.origin(getApplicationContext()),
                        AdSkipPreferences.generation(getApplicationContext()));
                store.save(saved, null);
            }
            if (saved.optString("signature").isEmpty() && !saved.has("job_id")
                    && !saved.optBoolean("admission_uncertain")) {
                saved.put("signature", AdSkipStore.signature(candidate));
                if (!terminal(saved.optString("status")) || saved.optBoolean("retry_pending")) {
                    saved.put("status", "WAITING");
                }
                saved.put("download_requested", false).put("download_suppressed", false);
                store.save(saved, saved.getString("generation"));
            }
            if (saved.has("result") || terminal(saved.optString("status"))
                    && !saved.optBoolean("retry_pending")) {
                continue;
            }
            media = candidate;
            record = saved;
            return true;
        }
        return false;
    }

    private boolean selectPriority(long mediaId, List<FeedItem> queue) throws IOException, JSONException {
        FeedMedia candidate = DBReader.getFeedMedia(mediaId);
        JSONObject saved = candidate == null ? store.read(mediaId) : store.readForMedia(candidate,
                AdSkipPreferences.origin(getApplicationContext()),
                AdSkipPreferences.generation(getApplicationContext()));
        if (saved == null) {
            return false;
        }
        boolean queued = queue.stream().anyMatch(item -> item.getMedia() != null && item.getMedia().getId() == mediaId);
        String reason = !AdSkipPreferences.isEnabled(getApplicationContext()) ? "analysis_disabled"
                : !queued ? "episode_not_queued"
                : candidate == null || !candidate.localFileAvailable() || !candidate.fileExists()
                        ? "audio_not_downloaded" : null;
        if (reason != null) {
            saved.put("last_dispatch_reason", reason);
            store.save(saved, saved.getString("generation"));
            return false;
        }
        if (candidate.getMediaType() != MediaType.AUDIO) {
            saved.put("last_dispatch_reason", "unsupported_media");
            store.save(saved, saved.getString("generation"));
            return false;
        }
        String signature = AdSkipStore.signature(candidate);
        if (signature.isEmpty()) {
            saved.put("last_dispatch_reason", "audio_not_downloaded");
            store.save(saved, saved.getString("generation"));
            return false;
        }
        if (!saved.has("job_id") && !saved.optBoolean("admission_uncertain")) {
            saved.put("signature", signature).put("status", "WAITING");
        }
        saved.remove("last_dispatch_reason");
        saved.put("last_dispatch_reason", "prioritized");
        store.save(saved, saved.getString("generation"));
        media = candidate;
        record = saved;
        return true;
    }

    private long advance() throws IOException, JSONException, GeneralSecurityException {
        Context context = getApplicationContext();
        Log.d(TAG, "advance: status=" + record.optString("status") + " job=" + record.optString("job_id", "(none)")
                + " retry=" + record.optBoolean("retry_pending") + " cancel=" + record.optBoolean("cancel_pending"));
        if (!record.has("job_id") && !record.optBoolean("admission_uncertain") && !record.has("sha256")) {
            record.put("origin", AdSkipPreferences.origin(context))
                    .put("configuration", AdSkipPreferences.generation(context));
        }
        if (record.optBoolean("retry_pending")
                && record.getString("origin").equals(AdSkipPreferences.origin(context))) {
            record.put("configuration", AdSkipPreferences.generation(context));
        }
        if (!record.getString("origin").equals(AdSkipPreferences.origin(context))
                || !record.getString("configuration").equals(AdSkipPreferences.generation(context))) {
            Log.w(TAG, "advance: configuration mismatch, origin match="
                    + record.getString("origin").equals(AdSkipPreferences.origin(context)));
            store.consumeRequest(record, "retry_pending");
            update(AdSkipState.CONFIGURATION);
            return 0;
        }
        if (record.optBoolean("retry_pending") && record.optBoolean("admission_uncertain")
                && !record.has("job_id") && media != null && !AdSkipStore.signature(media).isEmpty()
                && record.optString("sha256").equals(AdSkipStore.sha256(new File(media.getLocalFileUrl())))) {
            record.put("signature", AdSkipStore.signature(media)).put("local_invalid", false);
        }
        if (record.optBoolean("admission_uncertain") && !record.has("job_id") && !current()) {
            update(AdSkipState.RECOVERY);
            return 0;
        }
        if (record.optBoolean("cancel_pending") && !record.has("job_id")
                && !record.optBoolean("admission_uncertain")) {
            store.consumeRequest(record, "cancel_pending");
            update(AdSkipState.CANCELLED);
            return 0;
        }
        if (record.optBoolean("retry_pending") && !AdSkipPreferences.isEnabled(context)) {
            store.consumeRequest(record, "retry_pending");
            return 0;
        }
        if (!record.has("job_id") && !AdSkipDownloads.allowed(context)) {
            Log.i(TAG, "advance: network not allowed, deferring");
            update(AdSkipState.WAITING_NETWORK);
            return 60;
        }
        String token;
        try {
            token = AdSkipPreferences.token(context);
        } catch (IOException e) {
            throw new GeneralSecurityException("Credentials unavailable", e);
        }
        client = new AudioAdDetectClient(record.getString("origin"), token);
        if (record.optBoolean("retry_pending")) {
            record.put("corrupt_record", false).put("cancel_sent", false);
            if ("EXPIRED".equals(record.optString("status"))) {
                record.remove("job_id");
                record.remove("server_status");
                record.put("submission_key", UUID.randomUUID().toString());
            }
            if (record.has("job_id") && Arrays.asList("FAILED", "INTERRUPTED", "CANCELLED")
                    .contains(record.optString("server_status"))) {
                accept(client.action(record.getString("job_id"), "retry"));
            } else {
                record.put("network_attempts", 0);
                update(AdSkipState.WAITING);
            }
            store.consumeRequest(record, "retry_pending");
        }
        if (!record.has("job_id")) {
            File file = new File(media.getLocalFileUrl());
            if (file.length() == 0 || file.length() > 256L * 1024 * 1024) {
                update(AdSkipState.UNSUPPORTED);
                return 0;
            }
            long duration;
            try (MediaMetadataRetrieverCompat retriever = new MediaMetadataRetrieverCompat()) {
                retriever.setDataSource(file.getAbsolutePath());
                duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            }
            if (duration <= 0 || duration > 7200000) {
                update(AdSkipState.UNSUPPORTED);
                return 0;
            }
            String hash = AdSkipStore.sha256(file);
            if (!current()) {
                Log.w(TAG, "advance: media signature changed after hashing, deferring");
                record.put("deferred_reason", "media_changed_during_hash");
                update(AdSkipState.WAITING);
                return 0;
            }
            record.remove("deferred_reason");
            if (record.has("sha256") && !hash.equals(record.getString("sha256"))) {
                record.put("local_invalid", true);
                update(AdSkipState.RECOVERY);
                return 0;
            }
            record.put("sha256", hash).put("duration_ms", duration);
            if (isStopped() || (!record.optBoolean("admission_uncertain")
                    && (!AdSkipPreferences.isEnabled(context) || media.getItem() == null
                    || !DBReader.getQueueIDList().contains(media.getItem().getId())))) {
                Log.i(TAG, "advance: stopped or no longer queued/enabled, skipping upload");
                return 0;
            }
            record.put("admission_uncertain", true);
            update(AdSkipState.UPLOADING);
            uploading = client;
            try {
                if (!AdSkipDownloads.allowed(context)) {
                    Log.i(TAG, "advance: network disallowed at upload time, deferring");
                    update(AdSkipState.WAITING_NETWORK);
                    return 60;
                }
                Log.i(TAG, "advance: uploading " + file.length() + " bytes to " + record.optString("origin"));
                accept(client.upload(file, hash, record.getString("submission_key")));
            } finally {
                uploading = null;
            }
        } else if (record.optBoolean("cancel_pending") && !record.optBoolean("cancel_sent")) {
            try {
                accept(client.action(record.getString("job_id"), "cancel"));
            } catch (AudioAdDetectClient.ApiException exception) {
                if (exception.code != 409) {
                    throw exception;
                }
                accept(client.status(record.getString("job_id")));
                if (!"COMPLETED".equals(record.optString("status")) && !terminal(record.optString("status"))) {
                    throw exception;
                }
            }
            record.put("cancel_sent", true);
            if (!"COMPLETED".equals(record.optString("status")) && !terminal(record.optString("status"))) {
                update(AdSkipState.CANCELLING);
            }
        } else {
            accept(client.status(record.getString("job_id")));
        }
        record.put("network_attempts", 0).put("next_attempt", 0);
        if ("COMPLETED".equals(record.getString("status"))) {
            if (!current() || record.optBoolean("local_invalid")) {
                store.consumeRequest(record, "cancel_pending");
                update(AdSkipState.CANCELLED);
                return 0;
            }
            JSONObject result = client.result(record.getString("job_id"));
            String hash = AdSkipStore.sha256(new File(media.getLocalFileUrl()));
            if (!current() || !hash.equals(record.getString("sha256"))) {
                record.put("local_invalid", true).put("cancel_pending", true);
                persist();
                return 0;
            }
            AdSkipResultParser.parse(result, record.getString("job_id"), hash, record.getLong("duration_ms"));
            record.put("result", result);
            store.consumeRequest(record, "cancel_pending");
            update(switch (result.getString("outcome")) {
                case "valid_empty" -> AdSkipState.EMPTY;
                case "degraded_fallback" -> AdSkipState.DEGRADED;
                default -> AdSkipState.READY;
            });
            return 0;
        }
        if (terminal(record.optString("status"))) {
            store.consumeRequest(record, "cancel_pending");
            persist();
            return 0;
        }
        int polls = Math.min(6, record.optInt("polls") + 1);
        long delay = Math.min(60, polls * 10L);
        record.put("polls", polls).put("next_attempt", System.currentTimeMillis() + delay * 1000);
        persist();
        return delay;
    }

    private void accept(JSONObject response) throws JSONException, IOException {
        String jobId = response.getString("job_id");
        String status = response.getString("status");
        if (jobId.trim().isEmpty() || !Arrays.asList("queued", "running", "completed", "failed", "interrupted", "cancelled")
                .contains(status) || (record.has("job_id") && !jobId.equals(record.getString("job_id")))) {
            throw new JSONException("Invalid job response");
        }
        if (response.has("media_sha256") && !response.isNull("media_sha256")
                && !record.getString("sha256").equals(response.getString("media_sha256"))) {
            throw new JSONException("Invalid job hash");
        }
        record.put("job_id", jobId).put("status", status.toUpperCase(Locale.ROOT))
                .put("server_status", status.toUpperCase(Locale.ROOT)).put("admission_uncertain", false);
        record.put("stage", response.optString("stage", ""));
        persist();
    }

    private boolean current() throws JSONException {
        FeedMedia latest = DBReader.getFeedMedia(record.getLong("media_id"));
        return latest != null && record.getString("signature").equals(AdSkipStore.signature(latest));
    }

    private void persist() throws JSONException, IOException {
        if (!current() && (record.has("job_id") || record.optBoolean("admission_uncertain"))) {
            record.remove("result");
            record.put("local_invalid", true).put("cancel_pending", true);
        }
        store.save(record, record.getString("generation"));
    }

    private void update(AdSkipState state) {
        if (record == null) {
            return;
        }
        try {
            record.put("status", state.name());
            persist();
            EventBus.getDefault().post(new AdSkipEvent(record.optLong("media_id", -1)));
        } catch (IOException | JSONException ignored) {
            return;
        }
    }

    private long unavailable(long retryAfter) {
        if (record == null || isStopped()) {
            return 0;
        }
        try {
            int attempts = record.optInt("network_attempts") + 1;
            long delay = Math.max(Math.min(3600, retryAfter), Math.min(600, 10L << Math.min(attempts, 6)));
            record.put("network_attempts", attempts).put("next_attempt", System.currentTimeMillis() + delay * 1000);
            update(attempts >= 6 ? AdSkipState.FAILED : AdSkipState.UNAVAILABLE);
            return attempts >= 6 ? 0 : delay;
        } catch (JSONException e) {
            update(AdSkipState.INVALID);
            return 0;
        }
    }

    private static boolean terminal(String status) {
        return Arrays.asList("READY", "EMPTY", "DEGRADED", "FAILED", "INTERRUPTED", "CANCELLED",
                "INVALID", "UNSUPPORTED", "EXPIRED", "CREDENTIALS", "CONFIGURATION", "RECOVERY").contains(status);
    }

    static void stopUpload() {
        AudioAdDetectClient active = uploading;
        if (active != null) {
            active.cancel();
        }
    }

    @Override
    public void onStopped() {
        AudioAdDetectClient active = client;
        if (active != null) {
            active.cancel();
        }
    }
}
