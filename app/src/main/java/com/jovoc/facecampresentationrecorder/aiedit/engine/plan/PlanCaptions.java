package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

import java.util.Collections;
import java.util.List;

/** The captions of the whole video: one caption effect plus the words drawn bigger (transcript word indexes). */
public final class PlanCaptions {
    public final String effect;
    public final List<Integer> emphasisWords;

    public PlanCaptions(String effect, List<Integer> emphasisWords) {
        this.effect = effect;
        this.emphasisWords = Collections.unmodifiableList(emphasisWords);
    }

    public boolean isEmphasis(int wordIndex) {
        return emphasisWords.contains(wordIndex);
    }
}
