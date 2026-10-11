package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.util.ArrayList;
import java.util.List;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkInfo;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.event.adskip.AdSkipEvent;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import java.util.concurrent.TimeUnit;

public final class AdSkipCoordinator implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static AdSkipCoordinator instance;
    private static final ExecutorService scheduler = Executors.newSingleThreadExecutor();
    private static final String WORK_NAME = "audio-ad-detect";
    private final Context context;
    private final SharedPreferences preferences;

    private AdSkipCoordinator(Context context) {
        this.context = context.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        preferences.registerOnSharedPreferenceChangeListener(this);
        EventBus.getDefault().register(this);
        ConnectivityManager connectivity = (ConnectivityManager) this.context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        if (!AdSkipDownloads.allowed(AdSkipCoordinator.this.context)) {
                            AdSkipWorker.stopUpload();
                        }
                        schedule(AdSkipCoordinator.this.context, false, 0);
                    }

                    @Override
                    public void onLost(Network network) {
                        AdSkipWorker.stopUpload();
                    }
                });
        schedule(this.context, false, 0);
    }

    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new AdSkipCoordinator(context);
        }
    }

    public static void schedule(Context context, boolean append, long delaySeconds) {
        schedule(context, append, delaySeconds, new long[0]);
    }

    private static void schedule(Context context, boolean append, long delaySeconds, long[] resume) {
        scheduler.execute(() -> {
            try {
                AdSkipStore store = new AdSkipStore(context);
                for (long id : resume) {
                    JSONObject record = store.read(id);
                    if (record != null && record.optBoolean("download_suppressed")) {
                        store.request(id, false);
                    }
                }
                WorkManager manager = WorkManager.getInstance(context);
                List<WorkInfo> existing = manager.getWorkInfosForUniqueWork(WORK_NAME).get();
                if (existing.stream().anyMatch(info -> info.getState() == WorkInfo.State.ENQUEUED
                        || info.getState() == WorkInfo.State.BLOCKED)) {
                    return;
                }
                Constraints constraints = new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build();
                OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(AdSkipWorker.class)
                        .setConstraints(constraints)
                        .setInitialDelay(Math.max(0, Math.min(60, delaySeconds)), TimeUnit.SECONDS).build();
                manager.enqueueUniqueWork(WORK_NAME,
                        append ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.KEEP, work)
                        .getResult().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                return;
            }
        });
    }

    @Subscribe
    public void onQueueChanged(QueueEvent event) {
        List<FeedItem> additions = new ArrayList<>();
        if (event.action == QueueEvent.Action.ADDED && event.item != null) {
            additions.add(event.item);
        } else if ((event.action == QueueEvent.Action.ADDED_ITEMS || event.action == QueueEvent.Action.SET_QUEUE)
                && event.items != null) {
            additions.addAll(event.items);
        }
        long[] resume = additions.stream().filter(item -> item.getMedia() != null)
                .mapToLong(item -> item.getMedia().getId()).toArray();
        schedule(context, true, 0, resume);
    }

    @Subscribe
    public void onMediaChanged(FeedItemEvent event) {
        for (FeedItem item : event.items) {
            if (item.getMedia() != null) {
                EventBus.getDefault().post(new AdSkipEvent(item.getMedia().getId()));
            }
        }
        schedule(context, true, 0);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && (key.startsWith("adSkip") || UserPreferences.PREF_MOBILE_UPDATE.equals(key))) {
            if ("adSkipMobileData".equals(key) || UserPreferences.PREF_MOBILE_UPDATE.equals(key)) {
                AdSkipWorker.stopUpload();
                WorkManager.getInstance(context).cancelAllWorkByTag("adSkipDownload");
            }
            if ("adSkipEnabled".equals(key)) {
                AdSkipWorker.stopUpload();
            }
            if ("adSkipEnabled".equals(key) || "adSkipAutomatic".equals(key)) {
                EventBus.getDefault().post(new AdSkipEvent(-1));
            }
            schedule(context, true, 0);
        }
    }
}
