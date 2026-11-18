package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.Ease;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleDrawer;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitlePhrase;
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
 * ({@code c_emphasis_big}). When the plan's caption effect is {@code c_active_pop}, the active word also pops
 * ({@code scale_to}, 1.05 to 1.2, within {@code pop_ms}) each time it changes; {@code c_word_highlight} is the
 * drawer's own highlight of the active word. Same look as the manual editor's captions, so the result matches
 * what the user chose.
 */
public final class SubtitleCaptionPainter implements CaptionPainter {

    private static final double DEFAULT_EMPHASIS_SCALE = 1.3;
    private static final String ACTIVE_POP = "c_active_pop";
    private static final double DEFAULT_POP_SCALE = 1.12;
    private static final long DEFAULT_POP_MS = 120;
    /** The drawer only scales the active word when its style asks for more than 1, so a pop needs a hair above. */
    private static final float POP_STYLE_MIN_SCALE = 1.001f;

    private final SubtitleTrack track;
    private final SubtitleStyle style;
    private final SubtitleDrawer drawer = new SubtitleDrawer();
    private final EmphasisTarget target = new EmphasisTarget();
    /** The emphasis words' text objects; the drawer hands the very same object back when it draws the word. */
    private final Set<String> emphasized = Collections.newSetFromMap(new IdentityHashMap<String, Boolean>());
    private final float emphasisScale;
    private final boolean activePop;
    private final double popScale;
    private final long popMs;
    /** The style handed to the drawer: the user's, with the active-word scale kept above 1 while popping. */
    private final SubtitleStyle drawStyle;

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
        RegistryEntry pop = contract != null ? contract.effect(ACTIVE_POP) : null;
        activePop = plan.captions != null && ACTIVE_POP.equals(plan.captions.effect);
        popScale = pop != null ? Math.max(1.0, pop.defaultNumber("scale_to", DEFAULT_POP_SCALE)) : DEFAULT_POP_SCALE;
        popMs = pop != null ? Math.round(pop.defaultNumber("pop_ms", DEFAULT_POP_MS)) : DEFAULT_POP_MS;
        drawStyle = activePop && style.scaleActiveWord <= 1f
                ? style.withScaleActiveWord(POP_STYLE_MIN_SCALE) : style;
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
        target.pop = activePop ? popAt(outputMs) : 1f;
        return drawer.draw(target, track, drawStyle, outputMs, outputW, outputH);
    }

    /** The extra scale of the active word at {@code outputMs}: 1 when no word is active or its pop is over. */
    private float popAt(long outputMs) {
        SubtitlePhrase phrase = track.findPhraseAt(outputMs);
        int active = track.getActiveWordIndex(phrase, outputMs);
        if (active < 0 || active >= phrase.words.size()) return 1f;
        return popFactor(outputMs - phrase.words.get(active).startMs, popMs, popScale);
    }

    /**
     * The pop {@code ageMs} after a word became active: up to {@code scaleTo} in the first half of {@code popMs},
     * back to 1 in the second (out cubic both ways); 1 before and after.
     */
    static float popFactor(long ageMs, long popMs, double scaleTo) {
        if (ageMs < 0 || popMs <= 0 || ageMs >= popMs) return 1f;
        double p = (double) ageMs / popMs;
        double up = p < 0.5 ? Ease.outCubic(p * 2) : 1 - Ease.outCubic((p - 0.5) * 2);
        return (float) (1 + (scaleTo - 1) * up);
    }

    @Override
    public float captionTextSizePx(int outputW, int outputH) {
        return SubtitleDrawer.measureScaledFontSize(style.fontSizeSp, outputW);
    }

    /** Canvas target that draws emphasis words enlarged from their left baseline, into the room measured for them. */
    private final class EmphasisTarget implements SubtitleDrawer.DrawTarget {
        Canvas canvas;
        /** The pop of the active word this frame; the drawer's one scale call is for that word. */
        float pop = 1f;

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
            canvas.scale(sx * pop, sy * pop, px, py);
        }

        @Override
        public void restore() {
            canvas.restore();
        }
    }
}
