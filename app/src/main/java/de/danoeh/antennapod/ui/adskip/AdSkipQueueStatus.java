package de.danoeh.antennapod.ui.adskip;

import android.content.Context;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AdSkipQueueStatus {
    public enum Kind {
        QUEUED, IN_PROGRESS, FAILED, COMPLETE
    }

    public final Kind kind;
    public final String state;
    private final int stage;
    private final boolean outdated;

    private AdSkipQueueStatus(Kind kind, String state, int stage, boolean outdated) {
        this.kind = kind;
        this.state = state;
        this.stage = stage;
        this.outdated = outdated;
    }

    public static Map<Long, AdSkipQueueStatus> load(Context context, List<FeedItem> items) {
        AdSkipStore store = new AdSkipStore(context);
        Map<Long, AdSkipQueueStatus> statuses = new HashMap<>();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getMediaType() != MediaType.AUDIO) {
                continue;
            }
            try {
                JSONObject record = store.read(media.getId());
                if (record != null) {
                    statuses.put(media.getId(), fromRecord(context, media, record));
                }
            } catch (IOException | JSONException e) {
                statuses.put(media.getId(), new AdSkipQueueStatus(Kind.FAILED, "INVALID", 0, false));
            }
        }
        return statuses;
    }

    public boolean isWaitingForDownload() {
        return "WAITING_DOWNLOAD".equals(state);
    }

    public static AdSkipQueueStatus sourceProgress() {
        return new AdSkipQueueStatus(Kind.IN_PROGRESS, "SOURCE", R.string.ad_skip_stage_source, false);
    }

    public int iconResource() {
        return switch (kind) {
            case QUEUED -> R.drawable.ic_ad_skip_queued;
            case IN_PROGRESS -> R.drawable.ic_ad_skip_progress;
            case FAILED -> R.drawable.ic_error;
            case COMPLETE -> R.drawable.ic_ad_skip_complete;
        };
    }

    public String label(Context context) {
        return switch (kind) {
            case QUEUED -> context.getString(R.string.ad_skip_indicator_queued);
            case IN_PROGRESS -> context.getString(R.string.ad_skip_indicator_progress,
                    context.getString(stage));
            case FAILED -> context.getString(outdated ? R.string.ad_skip_indicator_outdated
                    : R.string.ad_skip_indicator_failed);
            case COMPLETE -> context.getString(R.string.ad_skip_indicator_complete);
        };
    }

    private static AdSkipQueueStatus fromRecord(Context context, FeedMedia media, JSONObject record) {
        String state = record.optString("status", "INVALID");
        String savedSignature = record.optString("signature", "");
        String currentSignature = AdSkipStore.signature(media);
        if (!record.optString("configuration").equals(AdSkipPreferences.generation(context))
                || !record.optString("origin").equals(AdSkipPreferences.origin(context))) {
            return new AdSkipQueueStatus(Kind.FAILED, state, 0, false);
        }
        boolean completed = "READY".equals(state) || "EMPTY".equals(state) || "DEGRADED".equals(state);
        if ((!savedSignature.isEmpty() && !savedSignature.equals(currentSignature))
                || (completed && !savedSignature.equals(currentSignature))) {
            return new AdSkipQueueStatus(Kind.FAILED, state, 0, true);
        }
        return switch (state) {
            case "WAITING_DOWNLOAD", "WAITING_NETWORK", "WAITING", "QUEUED", "UNAVAILABLE" ->
                    new AdSkipQueueStatus(Kind.QUEUED, state, 0, false);
            case "UPLOADING" -> new AdSkipQueueStatus(Kind.IN_PROGRESS, state, R.string.ad_skip_stage_upload, false);
            case "RUNNING" -> new AdSkipQueueStatus(Kind.IN_PROGRESS, state, stage(record.optString("stage")), false);
            case "COMPLETED" -> new AdSkipQueueStatus(Kind.IN_PROGRESS, state, R.string.ad_skip_stage_results, false);
            case "CANCELLING" -> new AdSkipQueueStatus(Kind.IN_PROGRESS, state,
                    R.string.ad_skip_stage_cancelling, false);
            case "READY", "EMPTY", "DEGRADED" -> new AdSkipQueueStatus(Kind.COMPLETE, state, 0, false);
            default -> new AdSkipQueueStatus(Kind.FAILED, state, 0, false);
        };
    }

    private static int stage(String value) {
        String stage = value.toLowerCase(Locale.ROOT);
        if (stage.contains("source") || stage.contains("download") || stage.contains("prepar")) {
            return R.string.ad_skip_stage_source;
        }
        if (stage.contains("upload")) {
            return R.string.ad_skip_stage_upload;
        }
        if (stage.contains("transcri") || stage.contains("speech")) {
            return R.string.ad_skip_stage_transcription;
        }
        if (stage.contains("detect") || stage.contains("infer") || stage.contains("discover")
                || stage.contains("refin")) {
            return R.string.ad_skip_stage_detection;
        }
        if (stage.contains("result") || stage.contains("validat") || stage.contains("fetch")) {
            return R.string.ad_skip_stage_results;
        }
        return R.string.ad_skip_stage_analysis;
    }
}
