package de.danoeh.antennapod.net.common.adskip;

import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class AudioAdDetectClient {
    public static final class ApiException extends IOException {
        public final int code;
        public final long retryAfterSeconds;

        ApiException(int code, long retryAfterSeconds) {
            super("Analysis HTTP " + code);
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private final OkHttpClient client;
    private final HttpUrl origin;
    private final String token;
    private volatile Call activeCall;

    public AudioAdDetectClient(String origin, String token) {
        this(origin, token, new OkHttpClient());
    }

    AudioAdDetectClient(String origin, String token, OkHttpClient baseClient) {
        client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(110, TimeUnit.SECONDS).writeTimeout(110, TimeUnit.SECONDS)
                .callTimeout(115, TimeUnit.SECONDS).build();
        this.origin = HttpUrl.get(AdSkipPreferences.validateOrigin(origin));
        this.token = token;
    }

    public JSONObject upload(File file, String sha256, String key) throws IOException, JSONException {
        HttpUrl url = route(null, null).newBuilder().addPathSegment("upload")
                .addQueryParameter("expected_sha256", sha256).build();
        return execute(new Request.Builder().url(url).header("Idempotency-Key", key)
                .post(RequestBody.create(file, MediaType.get("application/octet-stream"))), 202);
    }

    public JSONObject status(String jobId) throws IOException, JSONException {
        return execute(new Request.Builder().url(route(jobId, null)).get(), 200);
    }

    public JSONObject result(String jobId) throws IOException, JSONException {
        return execute(new Request.Builder().url(route(jobId, "result")).get(), 200);
    }

    public JSONObject action(String jobId, String action) throws IOException, JSONException {
        if (!"retry".equals(action) && !"cancel".equals(action)) {
            throw new IllegalArgumentException("Invalid action");
        }
        return execute(new Request.Builder().url(route(jobId, action))
                .post(RequestBody.create(new byte[0], null)), 202);
    }

    private HttpUrl route(String jobId, String operation) {
        HttpUrl.Builder builder = origin.newBuilder().addPathSegment("v1").addPathSegment("analyses");
        if (jobId != null) {
            builder.addPathSegment(jobId);
        }
        if (operation != null) {
            builder.addPathSegment(operation);
        }
        return builder.build();
    }

    private JSONObject execute(Request.Builder request, int expectedCode) throws IOException, JSONException {
        Call call = client.newCall(request.header("Authorization", "Bearer " + token).build());
        activeCall = call;
        try (Response response = call.execute()) {
            if (response.code() != expectedCode) {
                long retryAfter = 0;
                try {
                    retryAfter = Long.parseLong(response.header("Retry-After", "0"));
                } catch (NumberFormatException ignored) {
                    retryAfter = 0;
                }
                throw new ApiException(response.code(), Math.max(0, retryAfter));
            }
            if (response.body() == null) {
                throw new IOException("Missing response");
            }
            try (var stream = response.body().byteStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (body.size() + count > 4 * 1024 * 1024) {
                        throw new IOException("Response too large");
                    }
                    body.write(buffer, 0, count);
                }
                return new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            activeCall = null;
        }
    }

    public void cancel() {
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
    }
}
