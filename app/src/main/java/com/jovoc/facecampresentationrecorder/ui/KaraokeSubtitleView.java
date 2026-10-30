package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.jovoc.facecampresentationrecorder.subtitle.SubtitleDrawer;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleStyle;
import com.jovoc.facecampresentationrecorder.subtitle.SubtitleTrack;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

/**
 * Lightweight custom view for rendering real-time animated karaoke subtitles over video playback.
 *
 * <p>Delegates actual rendering to {@link SubtitleDrawer} on canvas draw passes.
 * Completely passes through all touch events so that underlying video gestures, playback taps,
 * and timeline controls are never obstructed.
 */
public class KaraokeSubtitleView extends View {

    private SubtitleTrack track;
    private SubtitleStyle style;
    private long playheadMs = -1;
    private final SubtitleDrawer drawer = new SubtitleDrawer();

    public KaraokeSubtitleView(Context context) {
        super(context);
        init();
    }

    public KaraokeSubtitleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public KaraokeSubtitleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    public void setTrack(SubtitleTrack track) {
        this.track = track;
        postInvalidateOnAnimation();
    }

    public void setStyle(SubtitleStyle style) {
        this.style = style;
        postInvalidateOnAnimation();
    }

    public void setPlayheadMs(long playheadMs) {
        if (this.playheadMs == playheadMs) return;
        this.playheadMs = playheadMs;
        postInvalidateOnAnimation();
    }

    public SubtitleTrack getTrack() {
        return track;
    }

    public SubtitleStyle getStyle() {
        return style;
    }

    public long getPlayheadMs() {
        return playheadMs;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (track != null && style != null && RecordSettings.subtitlesEnabled(getContext()) && playheadMs >= 0) {
            drawer.draw(canvas, track, style, playheadMs, getWidth(), getHeight());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }
}
