package com.jovoc.facecampresentationrecorder.aiedit.flow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One AI edit of one recording at one level, as {@link AiEditStore} keeps it so a job survives the process dying.
 * Holds only opaque ids, paths, ratios and codes: never the transcript, the plan's texts or the event log.
 * Mutable and used by one worker at a time; every change is saved by the caller through the store.
 */
public final class AiEditJob {

    /** Uploading what the server needs and creating the job. */
    public static final String STAGE_UPLOAD = "upload";
    /** Waiting for the server's plan. */
    public static final String STAGE_PLAN = "plan";
    /** Rendering the outputs on the phone. */
    public static final String STAGE_RENDER = "render";
    public static final String STAGE_DONE = "done";

    /** The file the plan is made for (the recording or the manual editor's result); never modified. */
    public final String sourcePath;
    public final String level;
    /** Names the recording to the server; with the level it is the job's identity there. */
    public final String sessionId;
    /** The workspace the job was made in; null uses the device's pick. */
    public final String workspaceId;
    /** The ratios to render, 9:16 first. */
    public final List<String> ratios;
    public final String textColors;
    public final boolean soundEffects;

    public String stage = STAGE_UPLOAD;
    /** An {@link AiEditException} code when the job stopped, else null. */
    public String error;
    public boolean errorRetryable;
    /** The caption of the recording to take the words from, if it has a READY one. */
    public String aiCaptionId;
    /** Set after the server lost the caption: the next try sends the audio instead. */
    public boolean forceAudio;
    public String audioUploadId;
    /** The proxy pieces uploaded so far, in time order; complete once it has {@link #proxyCount} entries. */
    public final List<String> proxyUploadIds = new ArrayList<>();
    /** How many pieces the proxy has (0 for Basic, or before the source was measured). */
    public int proxyCount;
    public String editId;
    /** Base path of the outputs without the ratio, e.g. {@code .../talk_aiedit}; chosen at the first render. */
    public String outputBase;
    /** The ratios whose file is complete. */
    public final List<String> doneRatios = new ArrayList<>();
    public long createdMs;
    public long updatedMs;

    public AiEditJob(String sourcePath, String level, String sessionId, String workspaceId,
                     List<String> ratios, String textColors, boolean soundEffects) {
        this.sourcePath = sourcePath;
        this.level = level;
        this.sessionId = sessionId;
        this.workspaceId = workspaceId;
        this.ratios = new ArrayList<>(ratios);
        this.textColors = textColors;
        this.soundEffects = soundEffects;
    }

    /** True while the job still has work to do: not finished and not stopped for a reason the user must act on. */
    public boolean isUnfinished() {
        return !STAGE_DONE.equals(stage);
    }

    /** True when the job stopped with an error and has not been started again. */
    public boolean hasFailed() {
        return error != null;
    }

    /** Clears the stop reason so the job runs again from its stage. */
    public void clearError() {
        error = null;
        errorRetryable = false;
    }

    /** The server forgot or failed the job and deleted its uploads: start over from the uploads. */
    public void resetForNewServerJob() {
        stage = STAGE_UPLOAD;
        editId = null;
        audioUploadId = null;
        proxyUploadIds.clear();
        clearError();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("source", sourcePath);
        o.put("level", level);
        o.put("session", sessionId);
        o.put("workspace", workspaceId == null ? JSONObject.NULL : workspaceId);
        o.put("ratios", new JSONArray(ratios));
        o.put("colors", textColors);
        o.put("sound", soundEffects);
        o.put("stage", stage);
        o.put("error", error == null ? JSONObject.NULL : error);
        o.put("retryable", errorRetryable);
        o.put("caption", aiCaptionId == null ? JSONObject.NULL : aiCaptionId);
        o.put("force_audio", forceAudio);
        o.put("audio", audioUploadId == null ? JSONObject.NULL : audioUploadId);
        o.put("proxies", new JSONArray(proxyUploadIds));
        o.put("proxy_count", proxyCount);
        o.put("edit", editId == null ? JSONObject.NULL : editId);
        o.put("out_base", outputBase == null ? JSONObject.NULL : outputBase);
        o.put("done", new JSONArray(doneRatios));
        o.put("created", createdMs);
        o.put("updated", updatedMs);
        return o;
    }

    public static AiEditJob fromJson(JSONObject o) throws JSONException {
        AiEditJob j = new AiEditJob(o.getString("source"), o.getString("level"), o.getString("session"),
                optString(o, "workspace"), strings(o.getJSONArray("ratios")), o.getString("colors"),
                o.getBoolean("sound"));
        j.stage = o.getString("stage");
        j.error = optString(o, "error");
        j.errorRetryable = o.optBoolean("retryable", false);
        j.aiCaptionId = optString(o, "caption");
        j.forceAudio = o.optBoolean("force_audio", false);
        j.audioUploadId = optString(o, "audio");
        j.proxyUploadIds.addAll(strings(o.getJSONArray("proxies")));
        j.proxyCount = o.optInt("proxy_count", 0);
        j.editId = optString(o, "edit");
        j.outputBase = optString(o, "out_base");
        j.doneRatios.addAll(strings(o.getJSONArray("done")));
        j.createdMs = o.optLong("created", 0);
        j.updatedMs = o.optLong("updated", 0);
        return j;
    }

    private static String optString(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }
}
