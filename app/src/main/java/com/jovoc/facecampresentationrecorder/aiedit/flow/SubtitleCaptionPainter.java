package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleDrawer;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleStyle;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleTrack;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The captions of an AI edit: the user's own subtitle style drawn by {@link SubtitleDrawer}, with the words moved
 * from source time to output time (words inside a cut are dropped) and the plan's emphasis words drawn bigger
 * ({@code c_emphasis_big}). Same look as the manual editor's captions, so the result matches what the user chose.
 */
public final class SubtitleCaptionPainter implements CaptionPainter {

    private static final double DEFAULT_EMPHASIS_SCALE = 1.3;

    private final SubtitleTrack track;
    private final SubtitleStyle style;
    private final SubtitleDrawer drawer = new SubtitleDrawer();
    private final EmphasisTarget target = new EmphasisTarget();
    /** The emphasis words' text objects; the drawer hands the very same object back when it draws the word. */
    private final Set<String> emphasized = Collections.newSetFromMap(new IdentityHashMap<String, Boolean>());
    private final float emphasisScale;

    /**
     * @param transcript the words on the source timeline (the caption of the recording)
     * @param timeline   the plan's cuts and speed, to put the words on the output timeline
     */
    public SubtitleCaptionPainter(SubtitleTrack transcript, SubtitleStyle style, Plan plan,
                                  OutputTimeline timeline, EngineContract contract) {
        this.style = style;
        RegistryEntry entry = contract != null ? contract.effect("c_emphasis_big") : null;
        double scale = entry != null ? entry.defaultNumber("scale_to", DEFAULT_EMPHASIS_SCALE) : DEFAULT_EMPHASIS_SCALE;
        this.emphasisScale = (float) Math.max(1.0, scale);
        List<SubtitleWord> words = outputWords(transcript, plan, timeline, emphasized);
        this.track = new SubtitleTrack(transcript.language, words);
        final SubtitleDrawer.TextMeasurer base = drawer.getTextMeasurer();
        drawer.setTextMeasurer(new SubtitleDrawer.TextMeasurer() {
            @Override
            public float measureText(String text, float textSizePx) {
                float w = base.measureText(text, textSizePx);
                return emphasized.contains(text) ? w * emphasisScale : w;
            }

            @Override
            public float getAscent(float textSizePx) {
                return base.getAscent(textSizePx);
            }

            @Override
            public float getDescent(float textSizePx) {
                return base.getDescent(textSizePx);
            }
        });
    }

    /**
     * The transcript words that survive the cuts, on the output timeline. The transcript's word index (words with
     * text only, as the server counts them) decides which are emphasis words; those get a text object of their own,
     * added to {@code emphasized}, so the drawer can tell them apart.
     */
    static List<SubtitleWord> outputWords(SubtitleTrack transcript, Plan plan, OutputTimeline timeline,
                                          Set<String> emphasized) {
        List<SubtitleWord> out = new ArrayList<>();
        int index = 0;
        for (SubtitleWord w : transcript.rawWords) {
            if (!w.isWord || w.text == null || w.text.trim().isEmpty()) continue;
            int wordIndex = index++;
            if (!timeline.isKept(w.startMs)) continue;
            long start = timeline.sourceToOutput(w.startMs);
            long end = Math.max(start + 1, timeline.sourceToOutput(w.endMs));
            String text = w.text;
            if (plan.captions != null && plan.captions.isEmphasis(wordIndex)) {
                text = new String(w.text);
                emphasized.add(text);
            }
            out.add(new SubtitleWord(text, start, end, true));
        }
        return out;
    }

    @Override
    public boolean draw(Canvas canvas, long outputMs, int outputW, int outputH) {
        target.canvas = canvas;
        return drawer.draw(target, track, style, outputMs, outputW, outputH);
    }

    @Override
    public float captionTextSizePx(int outputW, int outputH) {
        return SubtitleDrawer.measureScaledFontSize(style.fontSizeSp, outputW);
    }

    /** Canvas target that draws emphasis words enlarged from their left baseline, into the room measured for them. */
    private final class EmphasisTarget implements SubtitleDrawer.DrawTarget {
        Canvas canvas;

        @Override
        public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
            canvas.drawRoundRect(left, top, right, bottom, rx, ry, paint);
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            if (!emphasized.contains(text)) {
                canvas.drawText(text, x, y, paint);
                return;
            }
            canvas.save();
            canvas.scale(emphasisScale, emphasisScale, x, y);
            canvas.drawText(text, x, y, paint);
            canvas.restore();
        }

        @Override
        public void save() {
            canvas.save();
        }

        @Override
        public void scale(float sx, float sy, float px, float py) {
            canvas.scale(sx, sy, px, py);
        }

        @Override
        public void restore() {
            canvas.restore();
        }
    }
}
