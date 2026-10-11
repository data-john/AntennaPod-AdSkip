package de.danoeh.antennapod.storage.database.adskip;

import android.content.Context;
import android.util.AtomicFile;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AdSkipStore {
    private final File directory;

    public AdSkipStore(Context context) {
        directory = new File(context.getFilesDir(), "ad-skip");
    }

    public static String signature(FeedMedia media) {
        if (!media.localFileAvailable() || media.getLocalFileUrl() == null) {
            return "";
        }
        File file = new File(media.getLocalFileUrl());
        return file.isFile() ? file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified() : "";
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Hashing interrupted");
                    }
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private AtomicFile file(long mediaId) {
        return new AtomicFile(new File(directory, mediaId + ".json"));
    }

    public JSONObject read(long mediaId) throws IOException, JSONException {
        synchronized (AdSkipStore.class) {
            AtomicFile file = file(mediaId);
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
                return null;
            }
            JSONObject record = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (record.getInt("version") != 1 || record.getLong("media_id") != mediaId) {
                throw new JSONException("Invalid record");
            }
            record.getString("signature");
            for (String key : new String[]{"origin", "configuration", "generation", "submission_key", "status"}) {
                if (record.getString(key).trim().isEmpty()) {
                    throw new JSONException("Incomplete record");
                }
            }
            return record;
        }
    }

    public JSONObject readForMedia(FeedMedia media, String origin, String configuration)
            throws IOException, JSONException {
        try {
            return read(media.getId());
        } catch (IOException | JSONException e) {
            JSONObject record = create(media, origin, configuration).put("status", "INVALID").put("corrupt_record", true);
            save(record, null);
            return record;
        }
    }

    public JSONObject create(FeedMedia media, String origin, String configuration) throws JSONException {
        return new JSONObject().put("version", 1).put("media_id", media.getId())
                .put("signature", signature(media)).put("origin", origin).put("configuration", configuration)
                .put("generation", UUID.randomUUID().toString()).put("submission_key", UUID.randomUUID().toString())
                .put("status", "WAITING").put("network_attempts", 0).put("next_attempt", 0);
    }

    public boolean save(JSONObject record, String expectedGeneration) throws IOException, JSONException {
        synchronized (AdSkipStore.class) {
            long mediaId = record.getLong("media_id");
            if (expectedGeneration != null) {
                JSONObject current = read(mediaId);
                if (current == null || !expectedGeneration.equals(current.getString("generation"))) {
                    return false;
                }
                if (current.optBoolean("cancel_pending")) {
                    record.put("cancel_pending", true);
                }
                if (current.optBoolean("retry_pending")) {
                    record.put("retry_pending", true);
                }
            }
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Analysis storage unavailable");
            }
            record.put("updated", System.currentTimeMillis());
            AtomicFile file = file(mediaId);
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(record.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (IOException e) {
                file.failWrite(stream);
                throw e;
            }
            return true;
        }
    }

    public List<Long> mediaIds() {
        synchronized (AdSkipStore.class) {
            List<Long> result = new ArrayList<>();
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".json")) {
                        try {
                            result.add(Long.parseLong(file.getName().replace(".json", "")));
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                    }
                }
            }
            return result;
        }
    }

    public void delete(long mediaId) {
        synchronized (AdSkipStore.class) {
            file(mediaId).delete();
        }
    }

    public void suppressDownload(long mediaId) throws IOException, JSONException {
        synchronized (AdSkipStore.class) {
            JSONObject record = read(mediaId);
            if (record != null && record.optBoolean("download_requested")) {
                record.put("download_suppressed", true).put("status", "DOWNLOAD_FAILED");
                save(record, null);
            }
        }
    }

    public void request(long mediaId, boolean cancel) throws IOException, JSONException {
        synchronized (AdSkipStore.class) {
            JSONObject record = read(mediaId);
            if (record == null) {
                return;
            }
            record.put(cancel ? "cancel_pending" : "retry_pending", true);
            record.put(cancel ? "retry_pending" : "cancel_pending", false);
            record.put("next_attempt", 0).put("network_attempts", 0);
            save(record, null);
        }
    }

    public boolean consumeRequest(JSONObject record, String key) throws IOException, JSONException {
        synchronized (AdSkipStore.class) {
            JSONObject current = read(record.getLong("media_id"));
            if (current == null || !current.getString("generation").equals(record.getString("generation"))
                    || !current.optBoolean(key)) {
                return false;
            }
            current.put(key, false);
            save(current, null);
            record.put(key, false);
            return true;
        }
    }
}
