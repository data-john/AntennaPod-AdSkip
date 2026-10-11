package de.danoeh.antennapod.net.common.adskip;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
public class AudioAdDetectClientTest {
    @Test
    public void lostAdmissionResponseResendsExactBytesAndSameKey() throws Exception {
        byte[] bytes = {1, 7, 9, 0, 4};
        File file = File.createTempFile("adskip", ".audio");
        Files.write(file.toPath(), bytes);
        List<Request> requests = new ArrayList<>();
        OkHttpClient transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            requests.add(request);
            Buffer body = new Buffer();
            request.body().writeTo(body);
            assertArrayEquals(bytes, body.readByteArray());
            if (requests.size() == 1) {
                throw new IOException("Lost admission response");
            }
            return response(request, 202, "{\"job_id\":\"same-job\",\"status\":\"queued\"}");
        }).build();
        AudioAdDetectClient client = new AudioAdDetectClient("https://example.com", "test-token", transport);
        assertThrows(IOException.class, () -> client.upload(file, "hash", "persisted-key"));
        assertEquals("same-job", client.upload(file, "hash", "persisted-key").getString("job_id"));
        assertEquals(2, requests.size());
        for (Request request : requests) {
            assertEquals("persisted-key", request.header("Idempotency-Key"));
            assertEquals("Bearer test-token", request.header("Authorization"));
            assertEquals("/v1/analyses/upload", request.url().encodedPath());
            assertEquals("hash", request.url().queryParameter("expected_sha256"));
            assertEquals("application/octet-stream", request.body().contentType().toString());
        }
        file.delete();
    }

    @Test
    public void redirectsAndAccessErrorsAreNotFollowedOrParsedAsEmptyResults() {
        List<Request> requests = new ArrayList<>();
        OkHttpClient transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request());
            return response(chain.request(), 302, "").newBuilder()
                    .header("Location", "https://different-origin.example/result").build();
        }).build();
        AudioAdDetectClient client = new AudioAdDetectClient("https://example.com", "test-token", transport);
        AudioAdDetectClient.ApiException error = assertThrows(AudioAdDetectClient.ApiException.class,
                () -> client.result("job"));
        assertEquals(302, error.code);
        assertEquals(1, requests.size());
        assertThrows(IllegalArgumentException.class, () -> new AudioAdDetectClient("http://example.com", "token"));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioAdDetectClient("https://user:password@example.com", "token"));
    }

    private static Response response(Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
}
