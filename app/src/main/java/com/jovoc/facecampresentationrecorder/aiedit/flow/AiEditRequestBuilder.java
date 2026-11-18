package com.jovoc.facecampresentationrecorder.aiedit.flow;

import com.jovoc.facecampresentationrecorder.aiedit.capture.EventLogData;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.Capabilities;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleDrawer;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleStyle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the body of {@code POST /public/v1/ai-edits} (the contract's "Request"): the video's size, an output per
 * enabled ratio with its top-anchored crop and caption band, the facecam track and activity map from the event
 * log, the colour choice, what this build can draw and the sound option. Pure apart from the caption maths of
 * {@link SubtitleDrawer}, so it runs on a plain JVM. The body names no word of the recording.
 */
public final class AiEditRequestBuilder {

    private AiEditRequestBuilder() {}

    /** What the master (the file the plan is for) looks like. */
    public static final class Video {
        public final long durationMs;
        public final int width;
        public final int height;
        public final int fps;

        public Video(long durationMs, int width, int height, int fps) {
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    /**
     * @param job      the job; its ids name the transcript source and the proxy pieces
     * @param video    the file the plan is for
     * @param log      the event log in the time of {@code video}, or null (an imported or manually edited video)
     * @param style    the user's caption style, which decides where the caption band is
     * @param language ISO 639-3 code of the caption, or null
     */
    public static String build(AiEditJob job, Video video, EventLogData log, SubtitleStyle style,
                               EngineContract contract, Capabilities capabilities, String language)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("sessionId", job.sessionId);
        root.put("level", job.level);
        if (job.aiCaptionId != null && !job.forceAudio) {
            root.put("aiCaptionId", job.aiCaptionId);
        } else {
            root.put("audioUploadId", job.audioUploadId);
        }
        if (!AiEditSettings.LEVEL_BASIC.equals(job.level) && !job.proxyUploadIds.isEmpty()) {
            root.put("proxyUploadIds", new JSONArray(job.proxyUploadIds));
        }

        JSONObject request = new JSONObject();
        request.put("contract_version", contract.getVersion());
        request.put("video", new JSONObject()
                .put("duration_ms", video.durationMs)
                .put("width", video.width)
                .put("height", video.height)
                .put("fps", video.fps)
                .put("source", log != null ? "in_app_recording" : "imported"));
        JSONArray outputs = new JSONArray();
        for (String ratio : job.ratios) outputs.put(output(ratio, video, contract, style));
        request.put("outputs", outputs);
        request.put("facecam_track", log != null ? log.facecamTrackJson() : new JSONArray());
        request.put("activity_map", log != null ? log.activityMapJson() : new JSONArray());
        request.put("text_colors", job.textColors);
        if (language != null && language.length() == 3) request.put("language", language);
        request.put("capabilities", capabilities.toJson());
        request.put("options", new JSONObject().put("sound_effects", job.soundEffects));
        root.put("request", request);
        return root.toString();
    }

    private static JSONObject output(String ratio, Video video, EngineContract contract, SubtitleStyle style)
            throws JSONException {
        CropRect crop = OutputTimeline.cropFor(ratio, video.width, video.height);
        JSONObject o = new JSONObject();
        o.put("ratio", ratio);
        o.put("crop", new JSONObject()
                .put("x", round(crop.fractionX(video.width)))
                .put("y", round(crop.fractionY(video.height)))
                .put("w", round(crop.fractionW(video.width)))
                .put("h", round(crop.fractionH(video.height))));
        o.put("caption_band", captionBand(contract.output(ratio), style));
        return o;
    }

    /**
     * The band the user's captions take on this output, as fractions of its height: the caption's centre line
     * (where the style puts it for this shape) plus and minus its text and pill.
     */
    static JSONObject captionBand(OutputSpec spec, SubtitleStyle style) throws JSONException {
        float centerY = SubtitleDrawer.computeCenterY(spec.width, spec.height, style);
        float half = SubtitleDrawer.measureScaledFontSize(style.fontSizeSp, spec.width) * 0.75f;
        double y0 = Math.max(0.0, (centerY - half) / spec.height);
        double y1 = Math.min(1.0, (centerY + half) / spec.height);
        return new JSONObject().put("y0", round(y0)).put("y1", round(y1));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
