package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.event.adskip.AdSkipEvent;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import java.util.concurrent.TimeUnit;

public final class AdSkipCoordinator implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static AdSkipCoordinator instance;
    private static final String WORK_NAME = "audio-ad-detect";
    private final Context context;
    private final SharedPreferences preferences;

    private AdSkipCoordinator(Context context) {
        this.context = context.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        preferences.registerOnSharedPreferenceChangeListener(this);
        EventBus.getDefault().register(this);
        schedule(this.context, false, 0);
    }

    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new AdSkipCoordinator(context);
        }
    }

    public static void schedule(Context context, boolean append, long delaySeconds) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(AdSkipPreferences.mobileData(context)
                        ? NetworkType.CONNECTED : NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true).build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(AdSkipWorker.class)
                .setConstraints(constraints).setInitialDelay(Math.max(0, delaySeconds), TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,
                append ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.KEEP, work);
    }

    @Subscribe
    public void onQueueChanged(QueueEvent event) {
        schedule(context, false, 0);
    }

    @Subscribe
    public void onMediaChanged(FeedItemEvent event) {
        EventBus.getDefault().post(new AdSkipEvent(-1));
        schedule(context, false, 0);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.startsWith("adSkip")) {
            EventBus.getDefault().post(new AdSkipEvent(-1));
            schedule(context, true, 0);
        }
    }
}
