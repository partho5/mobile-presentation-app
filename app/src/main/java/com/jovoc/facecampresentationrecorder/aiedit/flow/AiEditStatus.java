package com.jovoc.facecampresentationrecorder.aiedit.flow;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where an AI edit stands on the server: the answer of {@code GET /public/v1/ai-edits/:id} (and the 202 of the
 * create, which has no plan). {@link #body} is the raw answer; once {@link #READY} it is the plan with its assets
 * and goes to {@code PlanParser.parse} as it is. Never logged: it carries the plan's texts.
 */
public final class AiEditStatus {

    public static final String PENDING = "PENDING";
    public static final String TRANSCRIBING = "TRANSCRIBING";
    public static final String PLANNING = "PLANNING";
    public static final String READY = "READY";
    public static final String NO_SPEECH = "NO_SPEECH";
    public static final String FAILED = "FAILED";

    public final String id;
    public final String status;
    /** Credits held for the job (0 once released). */
    public final int cost;
    /** The raw JSON answer. */
    public final String body;

    AiEditStatus(String id, String status, int cost, String body) {
        this.id = id;
        this.status = status;
        this.cost = cost;
        this.body = body;
    }

    /** True once the server will not change the answer any more. */
    public boolean isFinal() {
        return READY.equals(status) || NO_SPEECH.equals(status) || FAILED.equals(status);
    }

    /**
     * The progress stage a server status stands for. A status that shows no progress (final or unknown ones) keeps
     * {@code last}, so a new server word never makes the text jump back.
     */
    static AiEditListener.Stage stageFor(String status, AiEditListener.Stage last) {
        if (PENDING.equals(status)) return AiEditListener.Stage.QUEUED;
        if (TRANSCRIBING.equals(status)) return AiEditListener.Stage.TRANSCRIBING;
        if (PLANNING.equals(status)) return AiEditListener.Stage.PLANNING;
        return last;
    }

    static AiEditStatus parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        String id = o.optString("id", "");
        String status = o.optString("status", "");
        if (id.isEmpty() || status.isEmpty()) throw new JSONException("no edit");
        return new AiEditStatus(id, status, Math.max(0, o.optInt("cost", 0)), body);
    }
}
