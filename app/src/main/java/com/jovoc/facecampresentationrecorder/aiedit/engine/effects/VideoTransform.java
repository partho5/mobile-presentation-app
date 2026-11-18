package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

/**
 * How the video picture is drawn in one output frame: a zoom of {@code scale} (1 or more, so the frame stays full)
 * about a centre given as fractions of the output (x across, y down from the top), plus the look and layout parts
 * the other video effects add (flash, glitch, whip, shrunk frame with a facecam copy). Reused every frame.
 */
public final class VideoTransform {

    public double scale = 1;
    public double centerX = 0.5;
    public double centerY = 0.5;

    /** The whole picture shrunk about the output centre over a dimmed copy of itself (1 = full frame). */
    public double frameScale = 1;
    /** White laid over the frame, 0..1. */
    public double flash;
    /** Red and blue channel split, as a fraction of the output width (signed). */
    public double glitch;
    /** Picture slide, a fraction of the output width (signed); the compositor keeps it inside the zoomed picture. */
    public double shiftX;
    /** Horizontal motion blur, the length of the smear as a fraction of the output width. */
    public double blur;

    /** A round copy of the facecam drawn over the frame; all values are fractions of the output. */
    public boolean faceCopy;
    /** The facecam as it is in the picture: centre and radius (of the width). */
    public double faceSrcX;
    public double faceSrcY;
    public double faceSrcR;
    /** Where the copy is drawn: centre, and the radius as a fraction of the width and of the height. */
    public double faceX;
    public double faceY;
    public double faceRW;
    public double faceRH;

    /** Back to the plain picture. */
    public void reset() {
        scale = 1;
        centerX = 0.5;
        centerY = 0.5;
        frameScale = 1;
        flash = 0;
        glitch = 0;
        shiftX = 0;
        blur = 0;
        faceCopy = false;
    }

    /** Adds a zoom on top of what is already set; the latest centre wins. Never below 1. */
    public void zoom(double factor, double cx, double cy) {
        scale = Math.max(1, scale * factor);
        centerX = Ease.clamp01(cx);
        centerY = Ease.clamp01(cy);
    }

    /** Lays white over the frame; the strongest of the effects active at once wins. */
    public void flash(double alpha) {
        flash = Math.max(flash, Ease.clamp01(alpha));
    }

    /** Sets the facecam copy and the shrunk frame behind it (the later layout effect wins). */
    public void faceLayout(double frameScale, double srcX, double srcY, double srcR,
                           double x, double y, double rw, double rh) {
        this.frameScale = Math.max(0.5, Math.min(1, frameScale));
        faceCopy = true;
        faceSrcX = srcX;
        faceSrcY = srcY;
        faceSrcR = srcR;
        faceX = x;
        faceY = y;
        faceRW = rw;
        faceRH = rh;
    }

    public boolean isIdentity() {
        return scale == 1 && frameScale == 1 && flash == 0 && glitch == 0 && shiftX == 0 && blur == 0 && !faceCopy;
    }
}
