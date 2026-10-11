package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import de.danoeh.antennapod.model.MediaMetadataRetrieverCompat;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.event.adskip.AdSkipEvent;
import de.danoeh.antennapod.model.adskip.AdSkipState;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.net.common.NetworkUtils;
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
    private volatile AudioAdDetectClient client;
    private final AdSkipStore store;
    private JSONObject record;
    private FeedMedia media;

    public AdSkipWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
        store = new AdSkipStore(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        long delay = 0;
        try {
            if (!select()) {
                return Result.success();
            }
            if (record.optLong("next_attempt") > System.currentTimeMillis()) {
                delay = (record.getLong("next_attempt") - System.currentTimeMillis() + 999) / 1000;
            } else if (!NetworkUtils.isEpisodeDownloadAllowed()) {
                delay = 60;
            } else {
                delay = advance();
            }
        } catch (GeneralSecurityException e) {
            update(AdSkipState.CREDENTIALS);
        } catch (AudioAdDetectClient.ApiException e) {
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
            update(AdSkipState.INVALID);
        } catch (IOException e) {
            delay = unavailable(0);
        } finally {
            if (record != null) {
                EventBus.getDefault().post(new AdSkipEvent(record.optLong("media_id", -1)));
            }
        }
        if (!isStopped()) {
            AdSkipCoordinator.schedule(getApplicationContext(), true, delay);
        }
        return Result.success();
    }

    private boolean select() throws IOException, JSONException {
        List<FeedItem> queue = DBReader.getQueue();
        List<Long> queued = new ArrayList<>();
        for (FeedItem item : queue) {
            if (item.getMedia() != null) {
                queued.add(item.getMedia().getId());
            }
        }
        for (long id : store.mediaIds()) {
            FeedMedia candidate = DBReader.getFeedMedia(id);
            if (candidate == null || AdSkipStore.signature(candidate).isEmpty()) {
                store.delete(id);
                continue;
            }
            JSONObject saved;
            try {
                saved = store.read(id);
            } catch (JSONException | IOException e) {
                continue;
            }
            if (saved == null || !AdSkipStore.signature(candidate).equals(saved.optString("signature"))) {
                store.delete(id);
                continue;
            }
            if (!saved.has("result") && saved.has("job_id") && !terminal(saved.optString("status"))) {
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
                    || saved.optBoolean("retry_pending")) {
                media = candidate;
                record = saved;
                return true;
            }
        }
        for (long id : store.mediaIds()) {
            JSONObject saved = store.read(id);
            if (saved != null && saved.optBoolean("admission_uncertain") && terminal(saved.optString("status"))) {
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
                saved = store.read(candidate.getId());
            } catch (IOException | JSONException e) {
                continue;
            }
            if (saved == null) {
                saved = store.create(candidate, AdSkipPreferences.origin(getApplicationContext()),
                        AdSkipPreferences.generation(getApplicationContext()));
                store.save(saved, null);
            }
            if (saved.has("result") || terminal(saved.optString("status"))) {
                continue;
            }
            media = candidate;
            record = saved;
            return true;
        }
        return false;
    }

    private long advance() throws IOException, JSONException, GeneralSecurityException {
        Context context = getApplicationContext();
        if (record.optBoolean("retry_pending")
                && record.getString("origin").equals(AdSkipPreferences.origin(context))) {
            record.put("configuration", AdSkipPreferences.generation(context));
        }
        if (!record.getString("origin").equals(AdSkipPreferences.origin(context))
                || !record.getString("configuration").equals(AdSkipPreferences.generation(context))) {
            store.consumeRequest(record, "retry_pending");
            update(AdSkipState.CONFIGURATION);
            return 0;
        }
        if (record.optBoolean("cancel_pending") && !record.has("job_id")) {
            store.consumeRequest(record, "cancel_pending");
            update(AdSkipState.CANCELLED);
            return 0;
        }
        if (record.optBoolean("retry_pending") && !AdSkipPreferences.isEnabled(context)) {
            store.consumeRequest(record, "retry_pending");
            return 0;
        }
        String token;
        try {
            token = AdSkipPreferences.token(context);
        } catch (IOException e) {
            throw new GeneralSecurityException("Credentials unavailable", e);
        }
        client = new AudioAdDetectClient(record.getString("origin"), token);
        if (record.optBoolean("retry_pending")) {
            record.put("cancel_sent", false);
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
            if (duration <= 0 || duration > 3600000) {
                update(AdSkipState.UNSUPPORTED);
                return 0;
            }
            String hash = AdSkipStore.sha256(file);
            if (!current()) {
                return 0;
            }
            if (record.has("sha256") && !hash.equals(record.getString("sha256"))) {
                store.delete(media.getId());
                return 0;
            }
            record.put("sha256", hash).put("duration_ms", duration);
            if (isStopped() || !AdSkipPreferences.isEnabled(context)
                    || media.getItem() == null || !DBReader.getQueueIDList().contains(media.getItem().getId())) {
                return 0;
            }
            record.put("admission_uncertain", true);
            update(AdSkipState.UPLOADING);
            accept(client.upload(file, hash, record.getString("submission_key")));
        } else if (record.optBoolean("cancel_pending") && !record.optBoolean("cancel_sent")) {
            accept(client.action(record.getString("job_id"), "cancel"));
            record.put("cancel_sent", true);
            update(AdSkipState.CANCELLING);
        } else {
            accept(client.status(record.getString("job_id")));
        }
        record.put("network_attempts", 0).put("next_attempt", 0);
        if ("COMPLETED".equals(record.getString("status"))) {
            JSONObject result = client.result(record.getString("job_id"));
            String hash = AdSkipStore.sha256(new File(media.getLocalFileUrl()));
            if (!current() || !hash.equals(record.getString("sha256"))) {
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
        record.put("next_attempt", System.currentTimeMillis() + 10000);
        persist();
        return 10;
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
        FeedMedia latest = DBReader.getFeedMedia(media.getId());
        return latest != null && record.getString("signature").equals(AdSkipStore.signature(latest));
    }

    private void persist() throws JSONException, IOException {
        if (current()) {
            store.save(record, record.getString("generation"));
        }
    }

    private void update(AdSkipState state) {
        if (record == null) {
            return;
        }
        try {
            record.put("status", state.name());
            persist();
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
                "INVALID", "UNSUPPORTED", "EXPIRED", "CREDENTIALS", "CONFIGURATION").contains(status);
    }

    @Override
    public void onStopped() {
        AudioAdDetectClient active = client;
        if (active != null) {
            active.cancel();
        }
    }
}
