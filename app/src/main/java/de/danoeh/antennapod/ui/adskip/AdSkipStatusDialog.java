package de.danoeh.antennapod.ui.adskip;

import android.content.Context;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.adskip.AdSkipState;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.service.adskip.AdSkipCoordinator;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.json.JSONObject;

public final class AdSkipStatusDialog {
    private AdSkipStatusDialog() {
    }

    public static void show(Context context, FeedMedia media) {
        AdSkipStore store = new AdSkipStore(context);
        Single.fromCallable(() -> {
            JSONObject record = store.read(media.getId());
            return record == null ? new JSONObject().put("status", "WAITING") : record;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(record -> {
            int message = record.optBoolean("corrupt_record") ? R.string.ad_skip_corrupt_record
                    : message(record.optString("status"));
            String stage = record.optString("stage", "");
            String text = context.getString(message);
            if (!stage.trim().isEmpty() && stage.matches("[a-zA-Z0-9_ -]{1,80}")) {
                text = context.getString(R.string.ad_skip_status_stage, text, stage);
            }
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.ad_skip_title)
                    .setMessage(text).setPositiveButton(android.R.string.ok, null);
            if (record.has("media_id") && !record.has("result")) {
                builder.setNeutralButton(R.string.retry_label,
                        (dialog, which) -> request(context, store, media, false));
                builder.setNegativeButton(R.string.ad_skip_cancel_analysis,
                        (dialog, which) -> request(context, store, media, true));
            }
            builder.show();
        }, error -> Toast.makeText(context, R.string.ad_skip_invalid, Toast.LENGTH_LONG).show());
    }

    private static void request(Context context, AdSkipStore store, FeedMedia media, boolean cancel) {
        Single.fromCallable(() -> {
            store.request(media.getId(), cancel);
            return true;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(ignored -> {
            AdSkipCoordinator.schedule(context, true, 0);
            Toast.makeText(context, R.string.ad_skip_request_saved, Toast.LENGTH_SHORT).show();
        }, error -> Toast.makeText(context, R.string.ad_skip_invalid, Toast.LENGTH_LONG).show());
    }

    private static int message(String status) {
        try {
            return switch (AdSkipState.valueOf(status)) {
                case WAITING_DOWNLOAD -> R.string.ad_skip_waiting_download;
                case WAITING_NETWORK -> R.string.ad_skip_waiting_network;
                case DOWNLOAD_FAILED -> R.string.ad_skip_download_failed;
                case RECOVERY -> R.string.ad_skip_recovery;
                case WAITING -> R.string.ad_skip_waiting;
                case UPLOADING -> R.string.ad_skip_uploading;
                case QUEUED -> R.string.ad_skip_queued;
                case RUNNING -> R.string.ad_skip_running;
                case COMPLETED -> R.string.ad_skip_fetching;
                case READY -> R.string.ad_skip_ready;
                case EMPTY -> R.string.ad_skip_empty;
                case DEGRADED -> R.string.ad_skip_degraded;
                case UNAVAILABLE -> R.string.ad_skip_unavailable;
                case CREDENTIALS -> R.string.ad_skip_credentials;
                case UNSUPPORTED -> R.string.ad_skip_unsupported;
                case FAILED -> R.string.ad_skip_failed;
                case INTERRUPTED -> R.string.ad_skip_interrupted;
                case CANCELLED -> R.string.ad_skip_cancelled;
                case CANCELLING -> R.string.ad_skip_cancelling;
                case EXPIRED -> R.string.ad_skip_expired;
                case CONFIGURATION -> R.string.ad_skip_configuration;
                default -> R.string.ad_skip_invalid;
            };
        } catch (IllegalArgumentException e) {
            return R.string.ad_skip_invalid;
        }
    }
}
