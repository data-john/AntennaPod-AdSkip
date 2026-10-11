package de.danoeh.antennapod.net.common.adskip;

import de.danoeh.antennapod.model.adskip.AdSkipRanges;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public final class AdSkipResultParser {
    private AdSkipResultParser() {
    }

    public static AdSkipRanges parse(JSONObject result, String jobId, String sha256, long localDurationMs)
            throws JSONException {
        long duration = integer(result, "duration_ms");
        if (!"promotion-analysis-result-v1".equals(result.getString("schema_version"))
                || !jobId.equals(result.getString("job_id"))
                || !sha256.equals(result.getString("media_sha256"))
                || duration <= 0 || localDurationMs <= 0 || Math.abs(duration - localDurationMs) > 1000) {
            throw new JSONException("Result identity mismatch");
        }
        String outcome = result.getString("outcome");
        if (!Arrays.asList("valid_ranges", "valid_empty", "degraded_fallback").contains(outcome)) {
            throw new JSONException("Unknown outcome");
        }
        result.getJSONObject("runtime_identity");
        result.getJSONArray("provenance_ranges");
        result.getJSONArray("source_primary_labels");
        JSONArray input = result.getJSONArray("ranges");
        if (("valid_empty".equals(outcome) && input.length() != 0)
                || ("valid_ranges".equals(outcome) && input.length() == 0)) {
            throw new JSONException("Inconsistent outcome");
        }
        List<AdSkipRanges.Range> ranges = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject range = input.getJSONObject(i);
            range.getString("range_id");
            range.getString("source");
            ranges.add(new AdSkipRanges.Range(integer(range, "start_ms"), integer(range, "end_ms")));
        }
        try {
            return new AdSkipRanges(ranges, duration);
        } catch (IllegalArgumentException e) {
            throw new JSONException("Invalid ranges");
        }
    }

    private static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException("Expected integer");
        }
        return ((Number) value).longValue();
    }
}
