package de.danoeh.antennapod.net.download.service.adskip;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import org.robolectric.RuntimeEnvironment;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.common.adskip.AudioAdDetectClient;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.LongList;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class AdSkipWorkerTest {
    private Context context;
    private AdSkipStore store;
    private File file;
    private FeedMedia media;
    private MockedStatic<DBReader> database;
    private MockedStatic<AdSkipPreferences> preferences;
    private MockedStatic<NetworkUtils> network;
    private MockedStatic<AdSkipCoordinator> coordinator;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        store = new AdSkipStore(context);
        for (long id : store.mediaIds()) {
            store.delete(id);
        }
        file = File.createTempFile("adskip", ".audio", context.getCacheDir());
        Files.write(file.toPath(), new byte[]{1, 3, 5, 7});
        ShadowMediaMetadataRetriever.addMetadata(file.getAbsolutePath(),
                MediaMetadataRetriever.METADATA_KEY_DURATION, "10000");
        FeedItem item = Mockito.mock(FeedItem.class);
        media = Mockito.mock(FeedMedia.class);
        when(item.getId()).thenReturn(10L);
        when(item.getMedia()).thenReturn(media);
        when(media.getId()).thenReturn(20L);
        when(media.getItem()).thenReturn(item);
        when(media.localFileAvailable()).thenReturn(true);
        when(media.fileExists()).thenReturn(true);
        when(media.getLocalFileUrl()).thenReturn(file.getAbsolutePath());
        when(media.getMediaType()).thenReturn(MediaType.AUDIO);
        database = Mockito.mockStatic(DBReader.class);
        database.when(DBReader::getQueue).thenReturn(Collections.singletonList(item));
        database.when(DBReader::getQueueIDList).thenReturn(LongList.of(10L));
        database.when(() -> DBReader.getFeedMedia(20L)).thenReturn(media);
        preferences = Mockito.mockStatic(AdSkipPreferences.class);
        preferences.when(() -> AdSkipPreferences.isEnabled(context)).thenReturn(true);
        preferences.when(() -> AdSkipPreferences.origin(context)).thenReturn("https://example.com");
        preferences.when(() -> AdSkipPreferences.generation(context)).thenReturn("config");
        preferences.when(() -> AdSkipPreferences.token(context)).thenReturn("test-token");
        network = Mockito.mockStatic(NetworkUtils.class);
        network.when(NetworkUtils::isEpisodeDownloadAllowed).thenReturn(true);
        coordinator = Mockito.mockStatic(AdSkipCoordinator.class);
    }

    @After
    public void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        if (network != null) {
            network.close();
        }
        if (preferences != null) {
            preferences.close();
        }
        if (database != null) {
            database.close();
        }
        store.delete(20);
        file.delete();
    }

    @Test
    public void lostAdmissionRecoversSameKeyThenPollsPersistedJob() throws Exception {
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class,
                (client, initialization) -> when(client.upload(any(), anyString(), anyString()))
                        .thenThrow(new IOException("Lost response")))) {
            runWorker();
            assertEquals(1, clients.constructed().size());
            JSONObject first = store.read(20);
            assertEquals("UNAVAILABLE", first.getString("status"));
            assertTrue(first.getBoolean("admission_uncertain"));
            String key = first.getString("submission_key");
            store.request(20, false);
            assertEquals(key, store.read(20).getString("submission_key"));
        }
        JSONObject retry = store.read(20);
        String key = retry.getString("submission_key");
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class,
                (client, initialization) -> when(client.upload(eq(file), anyString(), eq(key)))
                        .thenReturn(job("queued")))) {
            runWorker();
            Mockito.verify(clients.constructed().get(0)).upload(eq(file), anyString(), eq(key));
            assertEquals("job", new AdSkipStore(context).read(20).getString("job_id"));
            assertFalse(store.read(20).getBoolean("admission_uncertain"));
        }
        store.request(20, false);
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class,
                (client, initialization) -> when(client.status("job")).thenReturn(job("running")))) {
            runWorker();
            Mockito.verify(clients.constructed().get(0)).status("job");
            Mockito.verify(clients.constructed().get(0), Mockito.never()).upload(any(), anyString(), anyString());
            assertEquals("RUNNING", store.read(20).getString("status"));
        }
    }

    @Test
    public void retryAllowsCancellationAgain() throws Exception {
        JSONObject record = savedJob("CANCELLED");
        record.put("cancel_sent", true);
        store.save(record, null);
        store.request(20, false);
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class,
                (client, initialization) -> {
                    when(client.action("job", "retry")).thenReturn(job("queued"));
                    when(client.status("job")).thenReturn(job("running"));
                })) {
            runWorker();
            Mockito.verify(clients.constructed().get(0)).action("job", "retry");
            assertFalse(store.read(20).getBoolean("cancel_sent"));
        }
        store.request(20, true);
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class,
                (client, initialization) -> when(client.action("job", "cancel")).thenReturn(job("cancelled")))) {
            runWorker();
            Mockito.verify(clients.constructed().get(0)).action("job", "cancel");
        }
    }

    @Test
    public void queueRemovalBeforeUploadDoesNotCreateUncertainAdmission() throws Exception {
        database.when(DBReader::getQueueIDList).thenReturn(new LongList());
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class)) {
            runWorker();
            assertFalse(store.read(20).optBoolean("admission_uncertain"));
            Mockito.verify(clients.constructed().get(0), Mockito.never()).upload(any(), anyString(), anyString());
        }
    }

    @Test
    public void outageRetriesStopAndPreserveAdmittedJob() throws Exception {
        savedJob("RUNNING");
        try (MockedConstruction<AudioAdDetectClient> clients = Mockito.mockConstruction(AudioAdDetectClient.class,
                (client, initialization) -> when(client.status("job")).thenThrow(new IOException("Offline")))) {
            for (int i = 0; i < 6; i++) {
                JSONObject record = store.read(20);
                record.put("next_attempt", 0);
                store.save(record, null);
                runWorker();
            }
            assertEquals("FAILED", store.read(20).getString("status"));
            assertEquals("job", store.read(20).getString("job_id"));
            assertFalse(store.read(20).has("result"));
            runWorker();
            assertEquals(6, clients.constructed().size());
        }
    }

    private JSONObject savedJob(String status) throws Exception {
        JSONObject record = store.create(media, "https://example.com", "config");
        record.put("sha256", AdSkipStore.sha256(file)).put("duration_ms", 10000)
                .put("job_id", "job").put("status", status).put("server_status", status);
        store.save(record, null);
        return record;
    }

    private static JSONObject job(String status) throws Exception {
        return new JSONObject().put("job_id", "job").put("status", status);
    }

    private void runWorker() {
        new AdSkipWorker(context, Mockito.mock(WorkerParameters.class)).doWork();
    }
}
