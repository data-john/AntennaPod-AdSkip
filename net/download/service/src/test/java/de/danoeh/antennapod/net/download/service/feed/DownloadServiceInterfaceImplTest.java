package de.danoeh.antennapod.net.download.service.feed;

import android.content.Context;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.common.util.concurrent.Futures;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class DownloadServiceInterfaceImplTest {
    private Context context;
    private FeedItem item;
    private FeedMedia media;
    private WorkManager manager;
    private AdSkipStore store;
    private MockedStatic<WorkManager> work;
    private MockedStatic<DBWriter> database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = new AdSkipStore(context);
        store.delete(20);
        item = Mockito.mock(FeedItem.class);
        media = Mockito.mock(FeedMedia.class);
        when(media.getId()).thenReturn(20L);
        when(media.getItem()).thenReturn(item);
        when(media.getDownloadUrl()).thenReturn("https://example.com/episode.mp3");
        manager = Mockito.mock(WorkManager.class);
        WorkInfo info = Mockito.mock(WorkInfo.class);
        when(info.getTags()).thenReturn(Set.of(DownloadServiceInterface.WORK_DATA_WAS_QUEUED));
        when(manager.getWorkInfosByTag(tag())).thenReturn(Futures.immediateFuture(List.of(info)));
        work = Mockito.mockStatic(WorkManager.class);
        work.when(() -> WorkManager.getInstance(context)).thenReturn(manager);
        database = Mockito.mockStatic(DBWriter.class);
        RxJavaPlugins.setComputationSchedulerHandler(scheduler -> Schedulers.trampoline());
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        database.close();
        work.close();
        store.delete(20);
    }

    @Test
    public void cancellationWithoutAnalysisRecordRemovesAutomaticallyQueuedItem() {
        cancelAndVerify();
    }

    @Test
    public void cancellationSuppressesHealthyAnalysisDownloadAndRemovesQueuedItem() throws Exception {
        store.save(store.create(media, "https://example.com", "config").put("download_requested", true), null);
        cancelAndVerify();
        assertTrue(store.read(20).getBoolean("download_suppressed"));
    }

    @Test
    public void corruptAnalysisRecordDoesNotBypassQueueCleanup() throws Exception {
        File record = new File(context.getFilesDir(), "ad-skip/20.json");
        assertTrue(record.getParentFile().isDirectory() || record.getParentFile().mkdirs());
        Files.write(record.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        cancelAndVerify();
    }

    @Test
    public void analysisStorageFailureDoesNotBypassQueueCleanup() throws Exception {
        try (MockedConstruction<AdSkipStore> stores = Mockito.mockConstruction(AdSkipStore.class,
                (mock, construction) -> Mockito.doThrow(new IOException("Analysis storage unavailable"))
                        .when(mock).suppressDownload(20))) {
            cancelAndVerify();
            Mockito.verify(stores.constructed().get(0)).suppressDownload(20);
        }
    }

    private void cancelAndVerify() {
        new DownloadServiceInterfaceImpl().cancel(context, media);
        Mockito.verify(manager).cancelAllWorkByTag(tag());
        database.verify(() -> DBWriter.removeQueueItem(context, false, item));
    }

    private String tag() {
        return DownloadServiceInterface.WORK_TAG_EPISODE_URL + media.getDownloadUrl();
    }
}
