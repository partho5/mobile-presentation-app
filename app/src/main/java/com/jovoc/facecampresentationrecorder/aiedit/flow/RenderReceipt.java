package com.jovoc.facecampresentationrecorder.aiedit.flow;

import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The receipt of one render attempt (api.ai-video-editing, {@code POST /ai-edits/:id/render-report}): what the
 * phone did with the plan it was served, so a failed or odd render can be traced to a decision. Codes, ids and
 * numbers only, in the charsets and lengths the server accepts: never a word of the recording, a plan text, a
 * path or the message of an exception.
 */
final class RenderReceipt {

    /** The ratios the server accepts, 9:16 first. */
    private static final String[] RATIOS = {"9:16", "4:5", "1:1"};
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern NOT_DEVICE = Pattern.compile("[^A-Za-z0-9._+ -]");
    private static final Pattern NOT_VERSION = Pattern.compile("[^A-Za-z0-9._+-]");
    static final int MAX_DEVICE = 64;
    static final int MAX_VERSION = 32;
    static final String UNKNOWN = "unknown";

    /** What happened to one ratio. */
    static final class Output {
        final String ratio;
        final boolean ok;
        /** One of our own codes when not ok, else null. */
        final String errorCode;
        final long durationMs;
        final long bytes;

        Output(String ratio, boolean ok, String errorCode, long durationMs, long bytes) {
            this.ratio = ratio;
            this.ok = ok;
            this.errorCode = errorCode;
            this.durationMs = durationMs;
            this.bytes = bytes;
        }

        static Output ok(String ratio, long durationMs, long bytes) {
            return new Output(ratio, true, null, durationMs, bytes);
        }

        static Output failed(String ratio, String errorCode, long durationMs) {
            return new Output(ratio, false, errorCode, durationMs, -1);
        }
    }

    private RenderReceipt() {
    }

    /**
     * The JSON body of the receipt. {@code servedSha256} is the hash of the plan body as the phone received it;
     * outputs past the three ratios, an unknown ratio, and an error code that is not a plain code are dropped.
     */
    static String build(String attemptId, String servedSha256, boolean parseOk, String appVersion, String device,
                        List<Output> outputs, List<RenderNotes.Note> notes) {
        try {
            JSONObject o = new JSONObject();
            o.put("attemptId", attemptId);
            o.put("served_sha256", servedSha256);
            o.put("parse_ok", parseOk);
            o.put("app_version", sanitizeVersion(appVersion));
            o.put("device", sanitizeDevice(device));
            JSONArray outs = new JSONArray();
            for (Output out : outputs) {
                if (outs.length() >= RATIOS.length) break;
                if (!knownRatio(out.ratio)) continue;
                JSONObject j = new JSONObject();
                j.put("ratio", out.ratio);
                j.put("ok", out.ok);
                if (!out.ok && out.errorCode != null && CODE.matcher(out.errorCode).matches()) {
                    j.put("error_code", out.errorCode);
                }
                if (out.durationMs >= 0) j.put("duration_ms", Math.min(out.durationMs, 86_400_000L));
                if (out.bytes >= 0) j.put("bytes", out.bytes);
                outs.put(j);
            }
            o.put("outputs", outs);
            JSONArray warns = new JSONArray();
            for (RenderNotes.Note n : notes) {
                if (warns.length() >= RenderNotes.MAX_NOTES) break;
                JSONObject j = new JSONObject();
                j.put("code", n.code);
                if (n.itemId != null) j.put("item_id", n.itemId);
                warns.put(j);
            }
            o.put("warnings", warns);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean knownRatio(String ratio) {
        for (String r : RATIOS) if (r.equals(ratio)) return true;
        return false;
    }

    /** The sha256 of {@code bytes} as 64 lowercase hex characters. */
    static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The sha256 of {@code body} in UTF-8, the bytes the server hashed. */
    static String sha256Hex(String body) {
        return sha256Hex(body.getBytes(StandardCharsets.UTF_8));
    }

    /** A device model the server accepts: letters, digits and {@code . _ + -} and spaces, at most 64 characters. */
    static String sanitizeDevice(String model) {
        String s = model == null ? "" : NOT_DEVICE.matcher(model).replaceAll("_").trim();
        if (s.length() > MAX_DEVICE) s = s.substring(0, MAX_DEVICE).trim();
        return s.isEmpty() ? UNKNOWN : s;
    }

    /** An app version the server accepts: letters, digits and {@code . _ + -}, at most 32 characters. */
    static String sanitizeVersion(String version) {
        String s = version == null ? "" : NOT_VERSION.matcher(version).replaceAll("_");
        if (s.length() > MAX_VERSION) s = s.substring(0, MAX_VERSION);
        return s.isEmpty() ? UNKNOWN : s;
    }
}
