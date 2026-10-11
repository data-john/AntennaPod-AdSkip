package de.danoeh.antennapod.playback.service.adskip;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import de.danoeh.antennapod.model.MediaMetadataRetrieverCompat;
import de.danoeh.antennapod.model.adskip.AdSkipRanges;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.adskip.AdSkipResultParser;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.adskip.AdSkipStore;
import org.json.JSONObject;
import java.io.File;
import java.util.Optional;

public final class AdSkipPlayback {
    private AdSkipPlayback() {
    }

    public static Optional<AdSkipRanges> load(Context context, long mediaId) {
        try {
            FeedMedia media = DBReader.getFeedMedia(mediaId);
            if (media == null || AdSkipStore.signature(media).isEmpty()) {
                return Optional.empty();
            }
            AdSkipStore store = new AdSkipStore(context);
            JSONObject record = store.read(mediaId);
            String signature = AdSkipStore.signature(media);
            if (record == null || !record.has("result") || !signature.equals(record.getString("signature"))) {
                return Optional.empty();
            }
            String hash = AdSkipStore.sha256(new File(media.getLocalFileUrl()));
            long duration;
            try (MediaMetadataRetrieverCompat retriever = new MediaMetadataRetrieverCompat()) {
                retriever.setDataSource(media.getLocalFileUrl());
                duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            }
            FeedMedia latest = DBReader.getFeedMedia(mediaId);
            if (latest == null || !signature.equals(AdSkipStore.signature(latest))) {
                return Optional.empty();
            }
            return Optional.of(AdSkipResultParser.parse(record.getJSONObject("result"),
                    record.getString("job_id"), hash, duration));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
