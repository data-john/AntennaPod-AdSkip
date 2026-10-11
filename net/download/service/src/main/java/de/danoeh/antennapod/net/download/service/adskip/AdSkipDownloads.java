package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import de.danoeh.antennapod.event.adskip.AdSkipEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

final class AdSkipDownloads {
    private AdSkipDownloads() {
    }

    static boolean allowed(Context context) {
        return NetworkUtils.isEpisodeDownloadAllowed()
                && (AdSkipPreferences.mobileData(context) || !NetworkUtils.isNetworkRestricted());
    }

    static boolean reconcile(Context context, AdSkipStore store, List<FeedItem> queue, long[] resume)
            throws IOException, JSONException {
        if (!AdSkipPreferences.isEnabled(context)) {
            return false;
        }
        boolean pending = false;
        for (FeedItem item : queue) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getMediaType() != MediaType.AUDIO
                    || !AdSkipStore.signature(media).isEmpty()) {
                continue;
            }
            JSONObject record;
            try {
                record = store.readForMedia(media, AdSkipPreferences.origin(context),
                        AdSkipPreferences.generation(context));
            } catch (IOException | JSONException e) {
                continue;
            }
            if (record == null) {
                record = store.create(media, AdSkipPreferences.origin(context), AdSkipPreferences.generation(context));
                store.save(record, null);
            }
            if (record.has("job_id") || record.optBoolean("admission_uncertain")
                    || ("INVALID".equals(record.optString("status")) && !record.optBoolean("retry_pending"))) {
                continue;
            }
            for (long id : resume) {
                if (id == media.getId() && record.optBoolean("download_suppressed")) {
                    record.put("download_requested", false).put("download_suppressed", false);
                }
            }
            if (record.optBoolean("cancel_pending")) {
                record.put("download_suppressed", true);
                store.consumeRequest(record, "cancel_pending");
            }
            if (record.optBoolean("retry_pending")) {
                record.put("download_requested", false).put("download_suppressed", false);
                store.consumeRequest(record, "retry_pending");
            }
            String policy = AdSkipPreferences.mobileData(context) + ":" + UserPreferences.isAllowMobileEpisodeDownload();
            if (record.has("download_policy") && !policy.equals(record.optString("download_policy"))
                    && !record.optBoolean("download_suppressed")) {
                record.put("download_requested", false);
            }
            record.put("download_policy", policy);
            String previous = record.optString("status");
            if (record.optBoolean("download_suppressed")) {
                record.put("status", "DOWNLOAD_FAILED");
            } else if (!allowed(context)) {
                record.put("status", "WAITING_NETWORK");
                pending = true;
            } else if (record.optBoolean("download_requested")) {
                try {
                    List<WorkInfo> work = WorkManager.getInstance(context)
                            .getWorkInfosForUniqueWork(media.getDownloadUrl()).get();
                    boolean active = work.stream().anyMatch(info -> !info.getState().isFinished());
                    if (!active && (!work.isEmpty()
                            || System.currentTimeMillis() - record.optLong("download_requested_at") > 60000)) {
                        record.put("download_suppressed", true).put("status", "DOWNLOAD_FAILED");
                    } else {
                        record.put("status", "WAITING_DOWNLOAD");
                        pending = true;
                    }
                } catch (ExecutionException e) {
                    pending = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download reconciliation interrupted", e);
                }
            } else {
                record.put("download_requested", true).put("download_requested_at", System.currentTimeMillis())
                        .put("status", "WAITING_DOWNLOAD");
                store.save(record, null);
                DownloadServiceInterface.get().download(context, item, AdSkipPreferences.mobileData(context));
                pending = true;
            }
            store.save(record, record.getString("generation"));
            if (!previous.equals(record.optString("status"))) {
                EventBus.getDefault().post(new AdSkipEvent(media.getId()));
            }
        }
        return pending;
    }
}
