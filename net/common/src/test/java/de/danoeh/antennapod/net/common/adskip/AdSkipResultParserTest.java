package de.danoeh.antennapod.net.common.adskip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AdSkipResultParserTest {
    private JSONObject result(String outcome, JSONArray ranges) throws JSONException {
        return new JSONObject().put("schema_version", "promotion-analysis-result-v1")
                .put("job_id", "job").put("media_sha256", "hash").put("duration_ms", 5000)
                .put("outcome", outcome).put("ranges", ranges).put("runtime_identity", new JSONObject())
                .put("provenance_ranges", new JSONArray()).put("source_primary_labels", new JSONArray());
    }

    private JSONObject range(Object start, Object end) throws JSONException {
        return new JSONObject().put("start_ms", start).put("end_ms", end)
                .put("range_id", "r").put("source", "union");
    }

    @Test
    public void validEmptyAndDegradedResultsRemainDistinct() throws JSONException {
        assertTrue(AdSkipResultParser.parse(result("valid_empty", new JSONArray()), "job", "hash", 5000).isEmpty());
        assertEquals(2000, AdSkipResultParser.parse(result("degraded_fallback",
                new JSONArray().put(range(1000, 2000))), "job", "hash", 5000).skipTarget(1500));
    }

    @Test
    public void associationAndUnknownSchemaAreRejected() throws JSONException {
        JSONObject result = result("valid_empty", new JSONArray());
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(result, "other", "hash", 5000));
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(result, "job", "other", 5000));
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(result, "job", "hash", 0));
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(result, "job", "hash", 3999));
        result.put("schema_version", "future");
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(result, "job", "hash", 5000));
    }

    @Test
    public void integerGeometryAndOutcomeAreStrict() throws JSONException {
        for (Object start : new Object[]{-1, 1000.5, "1000", 2000}) {
            JSONObject result = result("valid_ranges", new JSONArray().put(range(start, 2000)));
            assertThrows(JSONException.class, () -> AdSkipResultParser.parse(result, "job", "hash", 5000));
        }
        JSONObject beyondEnd = result("valid_ranges", new JSONArray().put(range(4000, 5001)));
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(beyondEnd, "job", "hash", 5000));
        JSONObject falseEmpty = result("valid_empty", new JSONArray().put(range(1000, 2000)));
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(falseEmpty, "job", "hash", 5000));
        JSONObject failed = result("failed", new JSONArray());
        assertThrows(JSONException.class, () -> AdSkipResultParser.parse(failed, "job", "hash", 5000));
    }
}
