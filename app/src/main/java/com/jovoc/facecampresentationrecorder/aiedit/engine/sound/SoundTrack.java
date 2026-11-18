package com.jovoc.facecampresentationrecorder.aiedit.engine.sound;

import com.jovoc.facecampresentationrecorder.aiedit.engine.overlay.AssetFiles;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanAsset;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;
import com.jovoc.facecampresentationrecorder.aiedit.engine.render.AudioPath;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sound effects of a plan as one source on the audio mix bus: every item with a {@code sound} plays its clip at
 * {@code item start + offset} on the output timeline, quietly under the voice (spec section 14, "a fixed low
 * level"). A sound whose file is missing or unreadable is left out. Each clip is read once however often it plays;
 * mixing allocates nothing.
 */
public final class SoundTrack implements AudioPath.MixSource {

    /** The level of every effect under the voice. */
    static final float GAIN = 0.35f;
    /** A clip fades in and out over a few ms so a cut-off start or end never clicks. */
    private static final double FADE_IN_MS = 2;
    private static final double FADE_OUT_MS = 6;

    private static final class Cue {
        final WavClip clip;
        final long startMs;

        Cue(WavClip clip, long startMs) {
            this.clip = clip;
            this.startMs = startMs;
        }
    }

    private final List<Cue> cues;

    private SoundTrack(List<Cue> cues) {
        this.cues = cues;
    }

    /**
     * The track of {@code plan}'s sounds, their files under {@code assetsDir}; null when no sound can play (none
     * planned, no assets folder, or no file readable), so the render adds nothing to the mix.
     */
    public static SoundTrack fromPlan(Plan plan, File assetsDir) {
        if (assetsDir == null) return null;
        Map<String, WavClip> clips = new HashMap<>();
        List<Cue> cues = new ArrayList<>();
        for (PlanItem item : plan.items) {
            if (item.soundId == null) continue;
            PlanAsset asset = plan.asset(item.soundId);
            if (asset == null || !"sound".equals(asset.kind)) continue;
            if (!clips.containsKey(item.soundId)) {
                clips.put(item.soundId, WavClip.read(AssetFiles.existing(assetsDir, item.soundId)));
            }
            WavClip clip = clips.get(item.soundId);
            if (clip != null) cues.add(new Cue(clip, item.startMs + item.soundOffsetMs));
        }
        return cues.isEmpty() ? null : new SoundTrack(cues);
    }

    /** How many sounds play. */
    public int cueCount() {
        return cues.size();
    }

    @Override
    public void mixInto(float[] acc, long firstFrame, int frames, int rate, int channels) {
        long end = firstFrame + frames;
        for (int k = 0; k < cues.size(); k++) {
            Cue cue = cues.get(k);
            WavClip clip = cue.clip;
            double step = (double) clip.rate / rate;
            long start = Math.round(cue.startMs * rate / 1000.0);
            long length = (long) Math.ceil(clip.frames / step);
            long from = Math.max(firstFrame, start);
            long to = Math.min(end, start + length);
            if (from >= to) continue;
            double fadeIn = Math.max(1, FADE_IN_MS * clip.rate / 1000.0);
            double fadeOut = Math.max(1, FADE_OUT_MS * clip.rate / 1000.0);
            for (long f = from; f < to; f++) {
                double pos = (f - start) * step;
                int i0 = (int) pos;
                float frac = (float) (pos - i0);
                float env = (float) Math.min(1.0, Math.min(pos / fadeIn, (clip.frames - pos) / fadeOut));
                if (env <= 0) continue;
                int base = (int) (f - firstFrame) * channels;
                for (int c = 0; c < channels; c++) {
                    float a = clip.sample(i0, c, channels);
                    float b = clip.sample(i0 + 1, c, channels);
                    acc[base + c] += GAIN * env * (a + (b - a) * frac);
                }
            }
        }
    }
}
