package com.jovoc.facecampresentationrecorder.tracker;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.ApiResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.function.Supplier;

/** Sends one batch body to the backend and says what to do with its rows next. */
final class TrackerApi {

    /** SENT: delete the rows. DROP: delete them too (the server will never take them). RETRY: keep them. */
    enum Outcome { SENT, DROP, RETRY }

    static final String PATH = "/tracker/batches";

    private final ApiClient client;
    private final Supplier<String> token;

    /** {@code token} may be null or return null: the batch is then sent anonymously. */
    TrackerApi(ApiClient client, Supplier<String> token) {
        this.client = client;
        this.token = token;
    }

    Outcome send(String body) {
        try {
            String t = token == null ? null : token.get();
            ApiResponse r = client.sendGzipJson(PATH, body,
                    t == null ? null : Collections.singletonMap("Authorization", "Bearer " + t));
            if (r.status == 401 && t != null) r = client.sendGzipJson(PATH, body, null);  // resend once anonymously
            if (r.isSuccess()) return Outcome.SENT;
            if (r.status == 400 || r.status == 413 || r.status == 422) return Outcome.DROP;
            return Outcome.RETRY;                                                          // 404, 429, 5xx
        } catch (IOException e) {
            return Outcome.RETRY;
        } catch (RuntimeException e) {
            return Outcome.RETRY;
        }
    }
}
