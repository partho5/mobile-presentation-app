package com.jovoc.facecampresentationrecorder.activitylog;

import android.content.Context;
import android.os.Build;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.db.ActivityEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Sends one batch to {@code POST /activity/batches}. Anonymous works; when the user is signed in the
 * access token goes along so the backend links the device's earlier events to the account. The token
 * comes from {@link AuthRepository#quietAccessToken()}, so a dead session never signs anyone out from
 * here, the batch just goes anonymous.
 */
final class ActivityLogApi {

    static final String PATH = "/activity/batches";

    /** What to do with the batch after an attempt. */
    enum Outcome {
        /** The server stored it (or already had it): delete the rows. */
        SENT,
        /** The server will never accept this batch (400 / 413): delete the rows, do not retry forever. */
        DROP,
        /** Offline, throttled or a server problem: keep the rows and try later. */
        RETRY
    }

    private final ApiClient client;
    private final AuthRepository auth;

    /** {@code auth} may be null: every batch is then sent anonymously. */
    ActivityLogApi(ApiClient client, AuthRepository auth) {
        this.client = client;
        this.auth = auth;
    }

    static ActivityLogApi create(Context context) {
        return new ActivityLogApi(new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEBUG),
                SessionManager.get(context).repository());
    }

    Outcome send(String batchId, String deviceId, List<ActivityEvent> rows) {
        String body;
        try {
            body = buildBody(batchId, deviceId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    "Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT,
                    Build.MANUFACTURER + " " + Build.MODEL, rows);
        } catch (JSONException e) {
            return Outcome.DROP;
        }
        String token = auth == null ? null : auth.quietAccessToken();
        Map<String, String> headers = token == null
                ? null : Collections.singletonMap("Authorization", "Bearer " + token);
        try {
            return classify(client.send("POST", PATH, body, headers));
        } catch (IOException e) {
            return Outcome.RETRY;
        }
    }

    static Outcome classify(ApiResponse r) {
        if (r.isSuccess()) return Outcome.SENT;
        if (r.status == 400 || r.status == 413) return Outcome.DROP;
        // 404 = the backend does not have the route yet; 429 / 5xx = try again later.
        return Outcome.RETRY;
    }

    /** The request body of the backend contract (api.activity-log). */
    static String buildBody(String batchId, String deviceId, String version, int build,
                            String os, String model, List<ActivityEvent> rows) throws JSONException {
        JSONArray events = new JSONArray();
        for (ActivityEvent e : rows) {
            JSONObject o = new JSONObject();
            o.put("t", e.ts);
            o.put("tz", e.tz);
            o.put("s", e.session == null ? "" : e.session);
            o.put("a", e.action);
            if (e.metaJson != null) {
                try {
                    o.put("m", new JSONObject(e.metaJson));
                } catch (JSONException ignored) {
                    // Unreadable details cost the details, not the event.
                }
            }
            events.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("batchId", batchId);
        root.put("deviceId", deviceId);
        root.put("app", new JSONObject().put("version", version).put("build", build));
        root.put("device", new JSONObject().put("os", os).put("model", model));
        root.put("events", events);
        return root.toString();
    }
}
