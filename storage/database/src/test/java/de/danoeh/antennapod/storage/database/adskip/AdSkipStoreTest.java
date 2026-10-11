package de.danoeh.antennapod.storage.database.adskip;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AdSkipStoreTest {
    @Test
    public void uncertainAdmissionRetainsKeyAndJobAcrossRestart() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        FeedMedia media = new FeedMedia(null, "https://example.com/audio", 0, "audio/mpeg");
        media.setId(42);
        AdSkipStore store = new AdSkipStore(context);
        store.delete(42);
        JSONObject record = store.create(media, "https://example.com", "config");
        store.save(record, null);
        String key = record.getString("submission_key");
        AdSkipStore restarted = new AdSkipStore(context);
        assertEquals(key, restarted.read(42).getString("submission_key"));
        record.put("job_id", "admitted-job");
        assertTrue(store.save(record, record.getString("generation")));
        assertEquals("admitted-job", restarted.read(42).getString("job_id"));
        store.delete(42);
    }

    @Test
    public void replacementRejectsLateResultsAndConcurrentCancelSurvives() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        FeedMedia media = new FeedMedia(null, "https://example.com/audio", 0, "audio/mpeg");
        media.setId(43);
        AdSkipStore store = new AdSkipStore(context);
        store.delete(43);
        JSONObject old = store.create(media, "https://example.com", "config");
        store.save(old, null);
        store.request(43, true);
        assertTrue(store.save(old, old.getString("generation")));
        assertTrue(store.read(43).getBoolean("cancel_pending"));
        JSONObject replacement = store.create(media, "https://example.com", "config");
        store.save(replacement, null);
        old.put("result", new JSONObject());
        assertFalse(store.save(old, old.getString("generation")));
        assertFalse(store.read(43).has("result"));
        store.delete(43);
    }

    @Test
    public void sameSizedReplacementHasDifferentHash() throws Exception {
        File media = File.createTempFile("adskip", ".audio");
        Files.write(media.toPath(), new byte[]{1, 2, 3});
        String hash = AdSkipStore.sha256(media);
        Files.write(media.toPath(), new byte[]{3, 2, 1});
        assertFalse(hash.equals(AdSkipStore.sha256(media)));
        assertTrue(media.delete());
    }
}
