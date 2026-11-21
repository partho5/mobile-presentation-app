package com.jovoc.facecampresentationrecorder.tracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Builds the batch body of the backend contract (see to-build/tasks.md). Pure Java. */
final class TrackerPayload {

    /** The app block of the envelope. */
    static final class AppInfo {
        final String version;
        final int build;
        final String pkg;

        AppInfo(String version, int build, String pkg) {
            this.version = version;
            this.build = build;
            this.pkg = pkg;
        }
    }

    /** The device block of the envelope. */
    static final class DeviceInfo {
        final String os;
        final int sdk;
        final String manufacturer;
        final String model;
        final String locale;

        DeviceInfo(String os, int sdk, String manufacturer, String model, String locale) {
            this.os = os;
            this.sdk = sdk;
            this.manufacturer = manufacturer;
            this.model = model;
            this.locale = locale;
        }
    }

    private TrackerPayload() { }

    /**
     * The envelope with the rows as its {@code activities}. A row that is not readable JSON is
     * skipped. Null when no row remains, so the caller drops the batch.
     */
    static String build(String batchId, String deviceId, long sentAt, AppInfo app, DeviceInfo device,
                        List<String> rowJsons) {
        if (rowJsons == null) return null;
        try {
            JSONArray activities = new JSONArray();
            for (String s : rowJsons) {
                if (s == null) continue;
                try {
                    activities.put(new JSONObject(s));
                } catch (JSONException ignored) {
                    // an unreadable row is left out
                }
            }
            if (activities.length() == 0) return null;

            JSONObject o = new JSONObject();
            o.put("batchId", batchId);
            o.put("deviceId", deviceId);
            o.put("sentAt", sentAt);
            if (app != null) {
                JSONObject a = new JSONObject();
                a.put("version", app.version);
                a.put("build", app.build);
                a.put("package", app.pkg);
                o.put("app", a);
            }
            if (device != null) {
                JSONObject d = new JSONObject();
                d.put("os", device.os);
                d.put("sdk", device.sdk);
                d.put("manufacturer", device.manufacturer);
                d.put("model", device.model);
                d.put("locale", device.locale);
                o.put("device", d);
            }
            o.put("activities", activities);
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }
}
