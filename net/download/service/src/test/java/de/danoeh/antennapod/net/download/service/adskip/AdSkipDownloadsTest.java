package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import com.google.common.util.concurrent.Futures;
import java.util.List;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class AdSkipDownloadsTest {
    private Context context;
    private AdSkipStore store;
    private FeedItem item;
    private FeedMedia media;
    private DownloadServiceInterface downloads;
    private WorkManager manager;
    private MockedStatic<AdSkipPreferences> preferences;
    private MockedStatic<NetworkUtils> network;
    private MockedStatic<UserPreferences> upstream;
    private MockedStatic<WorkManager> work;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = new AdSkipStore(context);
        for (long id : store.mediaIds()) {
            store.delete(id);
        }
        item = Mockito.mock(FeedItem.class);
        media = Mockito.mock(FeedMedia.class);
        when(item.getMedia()).thenReturn(media);
        when(media.getId()).thenReturn(20L);
        when(media.getMediaType()).thenReturn(MediaType.AUDIO);
        when(media.getDownloadUrl()).thenReturn("https://example.com/episode.mp3");
        downloads = Mockito.mock(DownloadServiceInterface.class);
        DownloadServiceInterface.setImpl(downloads);
        preferences = Mockito.mockStatic(AdSkipPreferences.class);
        preferences.when(() -> AdSkipPreferences.isEnabled(context)).thenReturn(true);
        preferences.when(() -> AdSkipPreferences.origin(context)).thenReturn("https://example.com");
        preferences.when(() -> AdSkipPreferences.generation(context)).thenReturn("config");
        upstream = Mockito.mockStatic(UserPreferences.class);
        network = Mockito.mockStatic(NetworkUtils.class);
        network.when(NetworkUtils::isEpisodeDownloadAllowed).thenReturn(true);
        manager = Mockito.mock(WorkManager.class);
        work = Mockito.mockStatic(WorkManager.class);
        work.when(() -> WorkManager.getInstance(context)).thenReturn(manager);
        when(manager.getWorkInfosForUniqueWork(media.getDownloadUrl()))
                .thenReturn(Futures.immediateFuture(Collections.emptyList()));
    }

    @After
    public void tearDown() {
        work.close();
        network.close();
        upstream.close();
        preferences.close();
        store.delete(20);
        DownloadServiceInterface.setImpl(null);
    }

    @Test
    public void duplicateStartupAndQueueEventsRequestDownloadOnceWithAutoSkipOff() throws Exception {
        assertTrue(reconcile(new long[0]));
        assertTrue(reconcile(new long[]{20}));
        assertTrue(reconcile(new long[0]));
        Mockito.verify(downloads).download(context, item, false);
        assertEquals("WAITING_DOWNLOAD", store.read(20).getString("status"));
        assertFalse(store.read(20).has("sha256"));
        assertEquals("", store.read(20).getString("signature"));
    }

    @Test
    public void meteredDownloadRequiresBothPermissions() throws Exception {
        network.when(NetworkUtils::isNetworkRestricted).thenReturn(true);
        assertTrue(reconcile(new long[0]));
        assertEquals("WAITING_NETWORK", store.read(20).getString("status"));
        Mockito.verifyNoInteractions(downloads);
        preferences.when(() -> AdSkipPreferences.mobileData(context)).thenReturn(true);
        network.when(NetworkUtils::isEpisodeDownloadAllowed).thenReturn(false);
        assertTrue(reconcile(new long[0]));
        Mockito.verifyNoInteractions(downloads);
        network.when(NetworkUtils::isEpisodeDownloadAllowed).thenReturn(true);
        assertTrue(reconcile(new long[0]));
        Mockito.verify(downloads).download(context, item, true);
    }

    @Test
    public void cancelledDownloadStaysSuppressedUntilAdditionOrRetry() throws Exception {
        reconcile(new long[0]);
        WorkInfo cancelled = Mockito.mock(WorkInfo.class);
        when(cancelled.getState()).thenReturn(WorkInfo.State.CANCELLED);
        when(manager.getWorkInfosForUniqueWork(media.getDownloadUrl()))
                .thenReturn(Futures.immediateFuture(List.of(cancelled)));
        assertFalse(reconcile(new long[0]));
        assertEquals("DOWNLOAD_FAILED", store.read(20).getString("status"));
        assertFalse(reconcile(new long[0]));
        Mockito.verify(downloads).download(context, item, false);
        assertTrue(reconcile(new long[]{20}));
        Mockito.verify(downloads, Mockito.times(2)).download(context, item, false);
        reconcile(new long[0]);
        store.request(20, false);
        assertTrue(reconcile(new long[0]));
        Mockito.verify(downloads, Mockito.times(3)).download(context, item, false);
    }

    @Test
    public void failedPartialDownloadNeverBecomesAnalysisEligible() throws Exception {
        when(media.fileExists()).thenReturn(true);
        reconcile(new long[0]);
        WorkInfo failed = Mockito.mock(WorkInfo.class);
        when(failed.getState()).thenReturn(WorkInfo.State.FAILED);
        when(manager.getWorkInfosForUniqueWork(media.getDownloadUrl()))
                .thenReturn(Futures.immediateFuture(List.of(failed)));
        assertFalse(reconcile(new long[0]));
        JSONObject record = store.read(20);
        assertTrue(record.getBoolean("download_suppressed"));
        assertEquals("", record.getString("signature"));
        assertFalse(record.has("result"));
    }

    @Test
    public void disabledIntegrationAndRemovedQueueStartNothing() throws Exception {
        preferences.when(() -> AdSkipPreferences.isEnabled(context)).thenReturn(false);
        assertFalse(reconcile(new long[]{20}));
        preferences.when(() -> AdSkipPreferences.isEnabled(context)).thenReturn(true);
        assertFalse(AdSkipDownloads.reconcile(context, store, Collections.emptyList(), new long[0]));
        Mockito.verifyNoInteractions(downloads);
    }

    @Test
    public void explicitCancellationSurvivesNetworkPreferenceChanges() throws Exception {
        reconcile(new long[0]);
        store.suppressDownload(20);
        preferences.when(() -> AdSkipPreferences.mobileData(context)).thenReturn(true);
        assertFalse(reconcile(new long[0]));
        assertTrue(store.read(20).getBoolean("download_suppressed"));
        Mockito.verify(downloads).download(context, item, false);
    }

    @Test
    public void bulkQueueReconciliationRequestsEveryMissingEpisodeOnce() throws Exception {
        FeedItem second = Mockito.mock(FeedItem.class);
        FeedMedia secondMedia = Mockito.mock(FeedMedia.class);
        when(second.getMedia()).thenReturn(secondMedia);
        when(secondMedia.getId()).thenReturn(21L);
        when(secondMedia.getMediaType()).thenReturn(MediaType.AUDIO);
        when(secondMedia.getDownloadUrl()).thenReturn("https://example.com/second.mp3");
        when(manager.getWorkInfosForUniqueWork(secondMedia.getDownloadUrl()))
                .thenReturn(Futures.immediateFuture(Collections.emptyList()));
        assertTrue(AdSkipDownloads.reconcile(context, store, List.of(item, second), new long[]{20, 21}));
        assertTrue(AdSkipDownloads.reconcile(context, store, List.of(second, item), new long[0]));
        Mockito.verify(downloads).download(context, item, false);
        Mockito.verify(downloads).download(context, second, false);
        store.delete(21);
    }

    private boolean reconcile(long[] resume) throws Exception {
        return AdSkipDownloads.reconcile(context, store, List.of(item), resume);
    }
}
