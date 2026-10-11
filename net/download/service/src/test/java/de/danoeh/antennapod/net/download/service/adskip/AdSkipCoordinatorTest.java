package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import android.net.ConnectivityManager;
import androidx.work.Configuration;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.event.adskip.AdSkipEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class AdSkipCoordinatorTest {
    @Test
    public void realSchedulerCoalescesStartupBulkSortAndSettingsWhileOffline() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        WorkManager.initialize(context, new Configuration.Builder().build());
        Constructor<AdSkipCoordinator> constructor = AdSkipCoordinator.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        AdSkipCoordinator coordinator = constructor.newInstance(context);
        EventCollector collector = new EventCollector();
        EventBus.getDefault().register(collector);
        try {
            WorkManager manager = WorkManager.getInstance(context);
            await().atMost(10, TimeUnit.SECONDS).until(() ->
                    manager.getWorkInfosForUniqueWork("audio-ad-detect").get().size() == 1);
            FeedItem item = Mockito.mock(FeedItem.class);
            FeedMedia media = Mockito.mock(FeedMedia.class);
            when(item.getMedia()).thenReturn(media);
            when(media.getId()).thenReturn(20L);
            AdSkipStore store = new AdSkipStore(context);
            JSONObject record = store.create(media, "https://example.com", "config");
            record.put("download_suppressed", true);
            store.save(record, null);
            coordinator.onQueueChanged(QueueEvent.sorted(List.of(item)));
            coordinator.onSharedPreferenceChanged(null, "adSkipMobileData");
            coordinator.onMediaChanged(new FeedItemEvent(List.of(item), false));
            coordinator.onQueueChanged(QueueEvent.setQueue(List.of(item)));
            await().atMost(10, TimeUnit.SECONDS).until(() -> store.read(20).optBoolean("retry_pending"));
            List<WorkInfo> work = manager.getWorkInfosForUniqueWork("audio-ad-detect").get();
            assertEquals(1, work.size());
            assertEquals(NetworkType.CONNECTED, work.get(0).getConstraints().getRequiredNetworkType());
            assertTrue(store.read(20).getBoolean("download_suppressed"));
            assertFalse(collector.ids.contains(-1L));
            assertEquals(List.of(20L), collector.ids);
        } finally {
            EventBus.getDefault().unregister(coordinator);
            EventBus.getDefault().unregister(collector);
        }
    }

    public static final class EventCollector {
        final List<Long> ids = new ArrayList<>();

        @Subscribe
        public void collect(AdSkipEvent event) {
            ids.add(event.mediaId);
        }
    }
}
