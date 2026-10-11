package de.danoeh.antennapod.playback.service.adskip;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import androidx.test.core.app.ApplicationProvider;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AdSkipPlaybackTest {
    @Test
    public void exactCachedRangesLoadOfflineAndRejectReplacedBytes() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File file = File.createTempFile("adskip", ".audio", context.getCacheDir());
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        ShadowMediaMetadataRetriever.addMetadata(file.getAbsolutePath(),
                MediaMetadataRetriever.METADATA_KEY_DURATION, "10000");
        FeedMedia media = new FeedMedia(null, "https://example.com/audio", 0, "audio/mpeg");
        media.setId(71);
        media.setLocalFileUrl(file.getAbsolutePath());
        media.setDownloaded(true, System.currentTimeMillis());
        AdSkipStore store = new AdSkipStore(context);
        JSONObject result = new JSONObject().put("schema_version", "promotion-analysis-result-v1")
                .put("job_id", "job").put("media_sha256", AdSkipStore.sha256(file)).put("duration_ms", 10000)
                .put("outcome", "valid_ranges").put("runtime_identity", new JSONObject())
                .put("provenance_ranges", new JSONArray()).put("source_primary_labels", new JSONArray())
                .put("ranges", new JSONArray().put(new JSONObject().put("range_id", "fixture")
                        .put("source", "fixture").put("start_ms", 1000).put("end_ms", 2000)));
        JSONObject record = store.create(media, "https://example.com", "config");
        record.put("job_id", "job").put("result", result);
        store.save(record, null);
        try (MockedStatic<DBReader> database = Mockito.mockStatic(DBReader.class)) {
            database.when(() -> DBReader.getFeedMedia(71L)).thenReturn(media);
            assertTrue(AdSkipPlayback.load(context, 71).isPresent());
            assertEquals(2000, AdSkipPlayback.load(context, 71).get().skipTarget(1500));
            assertEquals(-1, AdSkipPlayback.load(context, 71).get().skipTarget(2000));
            long modified = file.lastModified();
            Files.write(file.toPath(), new byte[]{3, 2, 1});
            assertTrue(file.setLastModified(modified));
            assertEquals(record.getString("signature"), AdSkipStore.signature(media));
            assertFalse(AdSkipPlayback.load(context, 71).isPresent());
            assertTrue(file.delete());
            assertFalse(AdSkipPlayback.load(context, 71).isPresent());
        } finally {
            store.delete(71);
            file.delete();
        }
    }
}
